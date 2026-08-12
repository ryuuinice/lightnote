use crate::error::Result;
use crate::models::{Attribute, Blob, Branch, EntityType, Note, Operation};
use crate::util::{now_ms, snapshot_hash, uuid_v7};
use rusqlite::{Connection, OptionalExtension};
use serde_json::{json, Value};

pub struct Change {
    pub change_id: String,
    pub origin_device_id: String,
    pub entity_type: EntityType,
    pub entity_id: String,
    pub operation: Operation,
    pub base_version: i64,
    pub version: i64,
    pub server_sequence: Option<i64>,
    pub content_hash: Option<String>,
    pub payload: Value,
    pub created_at: i64,
}

pub struct NewChange<'a> {
    pub origin_device_id: &'a str,
    pub entity_type: EntityType,
    pub entity_id: &'a str,
    pub operation: Operation,
    pub base_version: i64,
    pub version: i64,
    pub payload: &'a Value,
}

/// 在给定连接（事务）中生成并写入一条 Change Log；由调用方决定是否同时写 sync_outbox
pub fn record_change(conn: &Connection, input: &NewChange<'_>) -> Result<Change> {
    let change = Change {
        change_id: uuid_v7(),
        origin_device_id: input.origin_device_id.to_string(),
        entity_type: input.entity_type,
        entity_id: input.entity_id.to_string(),
        operation: input.operation,
        base_version: input.base_version,
        version: input.version,
        server_sequence: None,
        content_hash: Some(snapshot_hash(input.payload)),
        payload: input.payload.clone(),
        created_at: now_ms(),
    };
    insert_change_row(conn, &change)?;
    Ok(change)
}

/// 记录远端 Pull 的 Change（含服务端分配的 server_sequence）
pub fn record_pulled_change(conn: &Connection, c: &PulledChange) -> Result<()> {
    let change = Change {
        change_id: c.change_id.to_string(),
        origin_device_id: c.origin_device_id.to_string(),
        entity_type: c.entity_type,
        entity_id: c.entity_id.to_string(),
        operation: c.operation,
        base_version: c.version.saturating_sub(1),
        version: c.version,
        server_sequence: Some(c.server_sequence),
        content_hash: None,
        payload: c.payload.clone(),
        created_at: now_ms(),
    };
    insert_change_row(conn, &change)?;
    Ok(())
}

pub struct PulledChange<'a> {
    pub change_id: &'a str,
    pub origin_device_id: &'a str,
    pub entity_type: EntityType,
    pub entity_id: &'a str,
    pub operation: Operation,
    pub version: i64,
    pub server_sequence: i64,
    pub payload: &'a Value,
}

fn insert_change_row(conn: &Connection, c: &Change) -> Result<()> {
    conn.execute(
        "INSERT INTO entity_changes
         (change_id, origin_device_id, entity_type, entity_id, operation, base_version, version, server_sequence, content_hash, payload, created_at)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
        rusqlite::params![
            c.change_id,
            c.origin_device_id,
            c.entity_type.as_str(),
            c.entity_id,
            c.operation.as_str(),
            c.base_version,
            c.version,
            c.server_sequence,
            c.content_hash,
            c.payload.to_string(),
            c.created_at
        ],
    )?;
    Ok(())
}

pub fn change_exists(conn: &Connection, change_id: &str) -> Result<bool> {
    let n: i64 = conn.query_row(
        "SELECT COUNT(*) FROM entity_changes WHERE change_id = ?1",
        rusqlite::params![change_id],
        |r| r.get(0),
    )?;
    Ok(n > 0)
}

pub fn get_change(conn: &Connection, change_id: &str) -> Result<Option<Change>> {
    let c = conn
        .query_row(
            "SELECT change_id, origin_device_id, entity_type, entity_id, operation, base_version, version, server_sequence, content_hash, payload, created_at
             FROM entity_changes WHERE change_id = ?1",
            rusqlite::params![change_id],
            row_to_change,
        )
        .optional()?;
    Ok(c)
}

fn row_to_change(r: &rusqlite::Row<'_>) -> rusqlite::Result<Change> {
    let entity_type = EntityType::parse(&r.get::<_, String>(2)?)
        .ok_or_else(|| rusqlite::Error::FromSqlConversionFailure(2, rusqlite::types::Type::Text, "invalid entity_type".into()))?;
    let operation = Operation::parse(&r.get::<_, String>(4)?)
        .ok_or_else(|| rusqlite::Error::FromSqlConversionFailure(4, rusqlite::types::Type::Text, "invalid operation".into()))?;
    let payload: Value = serde_json::from_str(&r.get::<_, String>(9)?)
        .map_err(|e| rusqlite::Error::FromSqlConversionFailure(9, rusqlite::types::Type::Text, Box::new(e)))?;
    Ok(Change {
        change_id: r.get(0)?,
        origin_device_id: r.get(1)?,
        entity_type,
        entity_id: r.get(3)?,
        operation,
        base_version: r.get(5)?,
        version: r.get(6)?,
        server_sequence: r.get(7)?,
        content_hash: r.get(8)?,
        payload,
        created_at: r.get(10)?,
    })
}

pub fn list_changes_by_ids(conn: &Connection, ids: &[String]) -> Result<Vec<Change>> {
    if ids.is_empty() {
        return Ok(Vec::new());
    }
    let placeholders = ids.iter().map(|_| "?").collect::<Vec<_>>().join(",");
    let sql = format!(
        "SELECT change_id, origin_device_id, entity_type, entity_id, operation, base_version, version, server_sequence, content_hash, payload, created_at
         FROM entity_changes WHERE change_id IN ({placeholders})"
    );
    let mut stmt = conn.prepare(&sql)?;
    let params: Vec<&dyn rusqlite::ToSql> = ids.iter().map(|s| s as &dyn rusqlite::ToSql).collect();
    let rows = stmt.query_map(params.as_slice(), row_to_change)?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

pub fn backfill_server_sequence(conn: &Connection, change_id: &str, server_sequence: i64) -> Result<()> {
    conn.execute(
        "UPDATE entity_changes SET server_sequence = ?1 WHERE change_id = ?2 AND server_sequence IS NULL",
        rusqlite::params![server_sequence, change_id],
    )?;
    Ok(())
}

pub fn note_payload(n: &Note) -> Value {
    json!({
        "note_id": n.note_id,
        "title": n.title,
        "note_type": n.note_type,
        "blob_id": n.blob_id,
        "is_deleted": n.is_deleted,
        "conflict_of_note_id": n.conflict_of_note_id,
    })
}

pub fn branch_payload(b: &Branch) -> Value {
    json!({
        "branch_id": b.branch_id,
        "parent_note_id": b.parent_note_id,
        "child_note_id": b.child_note_id,
        "sort_order": b.sort_order,
        "is_deleted": b.is_deleted,
    })
}

pub fn attribute_payload(a: &Attribute) -> Value {
    json!({
        "attribute_id": a.attribute_id,
        "note_id": a.note_id,
        "attr_type": a.attr_type,
        "name": a.name,
        "value": a.value,
        "is_inherited": a.is_inherited,
        "is_deleted": a.is_deleted,
    })
}

pub fn blob_payload(b: &Blob) -> Value {
    json!({
        "blob_id": b.blob_id,
        "size": b.size,
        "mime_type": b.mime_type,
        "storage_type": b.storage_type,
    })
}
