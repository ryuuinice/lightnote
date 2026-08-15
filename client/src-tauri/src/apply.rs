use crate::change;
use crate::error::{Error, Result};
use crate::fts;
use crate::models::{EntityType, Operation};
use crate::repo;
use crate::sync::PullChange;
use crate::util::now_ms;
use rusqlite::{Connection, OptionalExtension};

/// Pull Version Guard 判定：change_id 去重 + local.version > change.version 禁止应用
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApplyDecision {
    Apply,
    SkipDuplicate,
    SkipNewerLocal,
}

fn parse_entity_type(s: &str) -> Result<EntityType> {
    EntityType::parse(s).ok_or_else(|| Error::Sync(format!("invalid entity_type in pull change: {s}")))
}

fn parse_operation(s: &str) -> Result<Operation> {
    Operation::parse(s).ok_or_else(|| Error::Sync(format!("invalid operation in pull change: {s}")))
}

fn local_version(conn: &Connection, entity_type: EntityType, entity_id: &str) -> Result<i64> {
    let sql = match entity_type {
        EntityType::Note => "SELECT version FROM notes WHERE note_id = ?1",
        EntityType::Branch => "SELECT version FROM branches WHERE branch_id = ?1",
        EntityType::Attribute => "SELECT version FROM attributes WHERE attribute_id = ?1",
        EntityType::Blob => "SELECT 1 FROM blobs WHERE blob_id = ?1",
    };
    let v = conn
        .query_row(sql, rusqlite::params![entity_id], |r| r.get::<_, i64>(0))
        .optional()?;
    Ok(match entity_type {
        EntityType::Blob => v.unwrap_or(0),
        _ => v.unwrap_or(0),
    })
}

pub fn decide(conn: &Connection, pc: &PullChange) -> Result<ApplyDecision> {
    if change::change_exists(conn, &pc.change_id)? {
        return Ok(ApplyDecision::SkipDuplicate);
    }
    let entity_type = parse_entity_type(&pc.entity_type)?;
    let local = local_version(conn, entity_type, &pc.entity_id)?;
    if local > pc.version {
        Ok(ApplyDecision::SkipNewerLocal)
    } else {
        Ok(ApplyDecision::Apply)
    }
}

fn record_pulled(conn: &Connection, pc: &PullChange) -> Result<()> {
    let entity_type = parse_entity_type(&pc.entity_type)?;
    let operation = parse_operation(&pc.operation)?;
    let origin = pc.origin_device_id.as_deref().unwrap_or("server");
    change::record_pulled_change(
        conn,
        &change::PulledChange {
            change_id: &pc.change_id,
            origin_device_id: origin,
            entity_type,
            entity_id: &pc.entity_id,
            operation,
            version: pc.version,
            server_sequence: pc.server_sequence,
            payload: &pc.payload,
        },
    )
}

fn apply_note(conn: &Connection, pc: &PullChange) -> Result<()> {
    let p = &pc.payload;
    let title = p.get("title").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let note_type = p.get("note_type").and_then(|v| v.as_str()).unwrap_or("text").to_string();
    let blob_id = p.get("blob_id").and_then(|v| v.as_str()).map(str::to_string);
    if let Some(bid) = blob_id.as_deref() {
        if !bid.is_empty() && !crate::util::valid_blob_id(bid) {
            return Err(Error::Sync(format!("invalid blob_id in pull payload: {bid}")));
        }
    }
    let conflict_of = p.get("conflict_of_note_id").and_then(|v| v.as_str()).map(str::to_string);
    let is_deleted = pc.operation == "DELETE" || p.get("is_deleted").and_then(|v| v.as_bool()).unwrap_or(false);
    let now = now_ms();
    conn.execute(
        "INSERT INTO notes (note_id, title, note_type, blob_id, is_deleted, version, updated_at, updated_by, created_at, conflict_of_note_id)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)
         ON CONFLICT(note_id) DO UPDATE SET
            title = excluded.title,
            note_type = excluded.note_type,
            blob_id = excluded.blob_id,
            is_deleted = excluded.is_deleted,
            version = excluded.version,
            updated_at = excluded.updated_at,
            updated_by = excluded.updated_by,
            conflict_of_note_id = excluded.conflict_of_note_id",
        rusqlite::params![
            pc.entity_id,
            title,
            note_type,
            blob_id,
            is_deleted as i64,
            pc.version,
            now,
            pc.origin_device_id,
            now,
            conflict_of
        ],
    )?;
    Ok(())
}

fn apply_branch(conn: &Connection, pc: &PullChange) -> Result<()> {
    let p = &pc.payload;
    let parent = p.get("parent_note_id").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let child = p.get("child_note_id").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let sort_order = p.get("sort_order").and_then(|v| v.as_i64()).unwrap_or(0);
    let is_deleted = pc.operation == "DELETE" || p.get("is_deleted").and_then(|v| v.as_bool()).unwrap_or(false);
    let now = now_ms();
    conn.execute(
        "INSERT INTO branches (branch_id, parent_note_id, child_note_id, sort_order, is_deleted, version, updated_at, updated_by, created_at)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)
         ON CONFLICT(branch_id) DO UPDATE SET
            parent_note_id = excluded.parent_note_id,
            child_note_id = excluded.child_note_id,
            sort_order = excluded.sort_order,
            is_deleted = excluded.is_deleted,
            version = excluded.version,
            updated_at = excluded.updated_at,
            updated_by = excluded.updated_by",
        rusqlite::params![
            pc.entity_id,
            parent,
            child,
            sort_order,
            is_deleted as i64,
            pc.version,
            now,
            pc.origin_device_id,
            now
        ],
    )?;
    Ok(())
}

fn apply_attribute(conn: &Connection, pc: &PullChange) -> Result<()> {
    let p = &pc.payload;
    let note_id = p.get("note_id").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let attr_type = p.get("attr_type").and_then(|v| v.as_str()).unwrap_or("meta").to_string();
    let name = p.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let value = p.get("value").and_then(|v| v.as_str()).map(str::to_string);
    let is_inherited = p.get("is_inherited").and_then(|v| v.as_bool()).unwrap_or(false);
    let is_deleted = pc.operation == "DELETE" || p.get("is_deleted").and_then(|v| v.as_bool()).unwrap_or(false);
    let now = now_ms();
    conn.execute(
        "INSERT INTO attributes (attribute_id, note_id, attr_type, name, value, is_inherited, is_deleted, version, updated_at, updated_by, created_at)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)
         ON CONFLICT(attribute_id) DO UPDATE SET
            note_id = excluded.note_id,
            attr_type = excluded.attr_type,
            name = excluded.name,
            value = excluded.value,
            is_inherited = excluded.is_inherited,
            is_deleted = excluded.is_deleted,
            version = excluded.version,
            updated_at = excluded.updated_at,
            updated_by = excluded.updated_by",
        rusqlite::params![
            pc.entity_id,
            note_id,
            attr_type,
            name,
            value,
            is_inherited as i64,
            is_deleted as i64,
            pc.version,
            now,
            pc.origin_device_id,
            now
        ],
    )?;
    Ok(())
}

fn apply_blob(conn: &Connection, pc: &PullChange) -> Result<()> {
    if !crate::util::valid_blob_id(&pc.entity_id) {
        return Err(Error::Sync(format!("invalid blob entity_id in pull change: {}", pc.entity_id)));
    }
    let now = now_ms();
    if pc.operation == "DELETE" {
        conn.execute(
            "DELETE FROM blobs WHERE blob_id = ?1",
            rusqlite::params![pc.entity_id],
        )?;
        return Ok(());
    }
    let p = &pc.payload;
    let size = p.get("size").and_then(|v| v.as_i64()).unwrap_or(0);
    let mime_type = p.get("mime_type").and_then(|v| v.as_str()).map(str::to_string);
    let storage_type = p.get("storage_type").and_then(|v| v.as_str()).unwrap_or("file").to_string();
    conn.execute(
        "INSERT INTO blobs (blob_id, size, mime_type, storage_type, storage_path, created_at)
         VALUES (?1,?2,?3,?4,?5,?6)
         ON CONFLICT(blob_id) DO UPDATE SET
            size = excluded.size,
            mime_type = excluded.mime_type,
            storage_type = excluded.storage_type",
        rusqlite::params![pc.entity_id, size, mime_type, storage_type, "", now],
    )?;
    Ok(())
}

/// Pull 应用：不写 sync_outbox；change_id 幂等 + Version Guard 两层防御
pub fn apply(conn: &Connection, pc: &PullChange) -> Result<ApplyDecision> {
    let decision = decide(conn, pc)?;
    match decision {
        ApplyDecision::SkipDuplicate => {
            change::backfill_server_sequence(conn, &pc.change_id, pc.server_sequence)?;
            return Ok(decision);
        }
        ApplyDecision::SkipNewerLocal => {
            record_pulled(conn, pc)?;
            return Ok(decision);
        }
        ApplyDecision::Apply => {}
    }
    let entity_type = parse_entity_type(&pc.entity_type)?;
    match entity_type {
        EntityType::Note => apply_note(conn, pc)?,
        EntityType::Branch => apply_branch(conn, pc)?,
        EntityType::Attribute => apply_attribute(conn, pc)?,
        EntityType::Blob => apply_blob(conn, pc)?,
    }
    match entity_type {
        EntityType::Note => fts::sync_note(conn, &pc.entity_id)?,
        EntityType::Attribute => {
            if let Some(note_id) = pc.payload.get("note_id").and_then(|v| v.as_str()) {
                fts::sync_note(conn, note_id)?;
            }
        }
        EntityType::Blob => {
            for note_id in repo::list_notes_by_blob(conn, &pc.entity_id)? {
                fts::sync_note(conn, &note_id)?;
            }
        }
        EntityType::Branch => {}
    }
    record_pulled(conn, pc)?;
    Ok(decision)
}
