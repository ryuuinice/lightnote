use crate::error::{Error, Result};
use crate::models::{Attribute, Blob, Branch, Note, NoteMeta, Tag};
use rusqlite::{Connection, OptionalExtension};

const ROOT_NOTE_ID: &str = "root";

fn row_to_note(r: &rusqlite::Row<'_>) -> rusqlite::Result<Note> {
    Ok(Note {
        note_id: r.get(0)?,
        title: r.get(1)?,
        note_type: r.get(2)?,
        blob_id: r.get(3)?,
        is_deleted: r.get::<_, i64>(4)? != 0,
        version: r.get(5)?,
        updated_at: r.get(6)?,
        updated_by: r.get(7)?,
        created_at: r.get(8)?,
        conflict_of_note_id: r.get(9)?,
    })
}

const NOTE_COLS: &str =
    "note_id, title, note_type, blob_id, is_deleted, version, updated_at, updated_by, created_at, conflict_of_note_id";
const NOTE_COLS_N: &str =
    "n.note_id, n.title, n.note_type, n.blob_id, n.is_deleted, n.version, n.updated_at, n.updated_by, n.created_at, n.conflict_of_note_id";

pub fn get_note(conn: &Connection, note_id: &str) -> Result<Option<Note>> {
    let note = conn
        .query_row(
            &format!("SELECT {NOTE_COLS} FROM notes WHERE note_id = ?1"),
            rusqlite::params![note_id],
            row_to_note,
        )
        .optional()?;
    Ok(note)
}

pub fn get_note_required(conn: &Connection, note_id: &str) -> Result<Note> {
    get_note(conn, note_id)?.ok_or_else(|| Error::NoteNotFound(note_id.to_string()))
}

pub fn delete_note_row(conn: &Connection, note_id: &str) -> Result<()> {
    conn.execute("DELETE FROM notes WHERE note_id = ?1", rusqlite::params![note_id])?;
    conn.execute("DELETE FROM branches WHERE child_note_id = ?1", rusqlite::params![note_id])?;
    Ok(())
}

pub fn insert_note(conn: &Connection, n: &Note) -> Result<()> {
    conn.execute(
        &format!(
            "INSERT INTO notes ({NOTE_COLS}) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)"
        ),
        rusqlite::params![
            n.note_id,
            n.title,
            n.note_type,
            n.blob_id,
            n.is_deleted as i64,
            n.version,
            n.updated_at,
            n.updated_by,
            n.created_at,
            n.conflict_of_note_id
        ],
    )?;
    Ok(())
}

pub fn update_note_title(conn: &Connection, note_id: &str, title: &str, version: i64, now: i64, updated_by: Option<&str>) -> Result<usize> {
    Ok(conn.execute(
        "UPDATE notes SET title = ?1, version = ?2, updated_at = ?3, updated_by = ?4 WHERE note_id = ?5",
        rusqlite::params![title, version, now, updated_by, note_id],
    )?)
}

pub fn set_note_blob(conn: &Connection, note_id: &str, blob_id: &str, version: i64, now: i64, updated_by: Option<&str>) -> Result<usize> {
    Ok(conn.execute(
        "UPDATE notes SET blob_id = ?1, version = ?2, updated_at = ?3, updated_by = ?4 WHERE note_id = ?5",
        rusqlite::params![blob_id, version, now, updated_by, note_id],
    )?)
}

pub fn set_note_deleted(conn: &Connection, note_id: &str, is_deleted: bool, version: i64, now: i64, updated_by: Option<&str>) -> Result<usize> {
    Ok(conn.execute(
        "UPDATE notes SET is_deleted = ?1, version = ?2, updated_at = ?3, updated_by = ?4 WHERE note_id = ?5",
        rusqlite::params![is_deleted as i64, version, now, updated_by, note_id],
    )?)
}

pub fn list_notes(conn: &Connection, parent_note_id: Option<&str>, include_deleted: bool) -> Result<Vec<NoteMeta>> {
    let deleted_filter = if include_deleted { "1" } else { "n.is_deleted = 0" };
    let parent = match parent_note_id {
        None | Some(ROOT_NOTE_ID) => None,
        Some(p) => Some(p),
    };
    let sql = if parent.is_some() {
        format!(
            "SELECT {NOTE_COLS_N}, b.sort_order FROM branches b
             JOIN notes n ON n.note_id = b.child_note_id
             WHERE b.parent_note_id = ?1 AND b.is_deleted = 0 AND {deleted_filter}
             ORDER BY b.sort_order ASC, n.updated_at DESC"
        )
    } else {
        format!(
            "SELECT {NOTE_COLS_N}, 0 FROM notes n
             WHERE {deleted_filter}
               AND NOT EXISTS (SELECT 1 FROM branches b WHERE b.child_note_id = n.note_id AND b.is_deleted = 0)
             ORDER BY n.updated_at DESC"
        )
    };
    let mut stmt = conn.prepare(&sql)?;
    let map = |r: &rusqlite::Row<'_>| -> rusqlite::Result<NoteMeta> {
        let note = row_to_note(r)?;
        let sort_order: i64 = r.get(10)?;
        Ok(note.meta(sort_order))
    };
    let rows = if let Some(p) = parent {
        stmt.query_map(rusqlite::params![p], map)?
    } else {
        stmt.query_map([], map)?
    };
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

pub fn list_trashed(conn: &Connection) -> Result<Vec<NoteMeta>> {
    let sql = format!(
        "SELECT {NOTE_COLS}, 0 FROM notes WHERE is_deleted = 1 ORDER BY updated_at DESC"
    );
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt.query_map([], |r| {
        let note = row_to_note(r)?;
        let sort_order: i64 = r.get(10)?;
        Ok(note.meta(sort_order))
    })?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

pub fn list_conflicts(conn: &Connection) -> Result<Vec<crate::models::ConflictInfo>> {
    let mut stmt = conn.prepare(
        "SELECT note_id, conflict_of_note_id, title, version, updated_at, updated_by
         FROM notes WHERE conflict_of_note_id IS NOT NULL ORDER BY updated_at DESC",
    )?;
    let rows = stmt.query_map([], |r| {
        Ok(crate::models::ConflictInfo {
            note_id: r.get(0)?,
            conflict_of_note_id: r.get(1)?,
            title: r.get(2)?,
            version: r.get(3)?,
            updated_at: r.get(4)?,
            updated_by: r.get(5)?,
        })
    })?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

fn row_to_branch(r: &rusqlite::Row<'_>) -> rusqlite::Result<Branch> {
    Ok(Branch {
        branch_id: r.get(0)?,
        parent_note_id: r.get(1)?,
        child_note_id: r.get(2)?,
        sort_order: r.get(3)?,
        is_deleted: r.get::<_, i64>(4)? != 0,
        version: r.get(5)?,
        updated_at: r.get(6)?,
        updated_by: r.get(7)?,
        created_at: r.get(8)?,
    })
}

pub fn find_branch_for_note(conn: &Connection, note_id: &str) -> Result<Option<Branch>> {
    let mut stmt = conn
        .prepare(
            "SELECT branch_id, parent_note_id, child_note_id, sort_order, is_deleted, version, updated_at, updated_by, created_at
             FROM branches WHERE child_note_id = ?1 AND is_deleted = 0 ORDER BY created_at LIMIT 1",
        )
        .expect("prepare find branch");
    let mut rows = stmt.query_map(rusqlite::params![note_id], row_to_branch)?;
    rows.next()
        .transpose()
        .map_err(|e| crate::Error::Database(e))
}

pub fn get_branch(conn: &Connection, branch_id: &str) -> Result<Option<Branch>> {
    let b = conn
        .query_row(
            "SELECT branch_id, parent_note_id, child_note_id, sort_order, is_deleted, version, updated_at, updated_by, created_at
             FROM branches WHERE branch_id = ?1",
            rusqlite::params![branch_id],
            row_to_branch,
        )
        .optional()?;
    Ok(b)
}

pub fn get_branch_required(conn: &Connection, branch_id: &str) -> Result<Branch> {
    get_branch(conn, branch_id)?.ok_or_else(|| Error::BranchNotFound(branch_id.to_string()))
}

pub fn insert_branch(conn: &Connection, b: &Branch) -> Result<()> {
    conn.execute(
        "INSERT INTO branches (branch_id, parent_note_id, child_note_id, sort_order, is_deleted, version, updated_at, updated_by, created_at)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",
        rusqlite::params![
            b.branch_id,
            b.parent_note_id,
            b.child_note_id,
            b.sort_order,
            b.is_deleted as i64,
            b.version,
            b.updated_at,
            b.updated_by,
            b.created_at
        ],
    )?;
    Ok(())
}

pub fn move_branch(conn: &Connection, branch_id: &str, new_parent_note_id: &str, new_sort_order: i64, version: i64, now: i64, updated_by: Option<&str>) -> Result<usize> {
    Ok(conn.execute(
        "UPDATE branches SET parent_note_id = ?1, sort_order = ?2, version = ?3, updated_at = ?4, updated_by = ?5 WHERE branch_id = ?6",
        rusqlite::params![new_parent_note_id, new_sort_order, version, now, updated_by, branch_id],
    )?)
}

pub fn set_branch_deleted(conn: &Connection, branch_id: &str, version: i64, now: i64, updated_by: Option<&str>) -> Result<usize> {
    Ok(conn.execute(
        "UPDATE branches SET is_deleted = 1, version = ?1, updated_at = ?2, updated_by = ?3 WHERE branch_id = ?4",
        rusqlite::params![version, now, updated_by, branch_id],
    )?)
}

fn row_to_attribute(r: &rusqlite::Row<'_>) -> rusqlite::Result<Attribute> {
    Ok(Attribute {
        attribute_id: r.get(0)?,
        note_id: r.get(1)?,
        attr_type: r.get(2)?,
        name: r.get(3)?,
        value: r.get(4)?,
        is_inherited: r.get::<_, i64>(5)? != 0,
        is_deleted: r.get::<_, i64>(6)? != 0,
        version: r.get(7)?,
        updated_at: r.get(8)?,
        updated_by: r.get(9)?,
        created_at: r.get(10)?,
    })
}

pub fn get_attribute(conn: &Connection, attribute_id: &str) -> Result<Option<Attribute>> {
    let a = conn
        .query_row(
            "SELECT attribute_id, note_id, attr_type, name, value, is_inherited, is_deleted, version, updated_at, updated_by, created_at
             FROM attributes WHERE attribute_id = ?1",
            rusqlite::params![attribute_id],
            row_to_attribute,
        )
        .optional()?;
    Ok(a)
}

pub fn get_attribute_required(conn: &Connection, attribute_id: &str) -> Result<Attribute> {
    get_attribute(conn, attribute_id)?.ok_or_else(|| Error::AttributeNotFound(attribute_id.to_string()))
}

pub fn insert_attribute(conn: &Connection, a: &Attribute) -> Result<()> {
    conn.execute(
        "INSERT INTO attributes (attribute_id, note_id, attr_type, name, value, is_inherited, is_deleted, version, updated_at, updated_by, created_at)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
        rusqlite::params![
            a.attribute_id,
            a.note_id,
            a.attr_type,
            a.name,
            a.value,
            a.is_inherited as i64,
            a.is_deleted as i64,
            a.version,
            a.updated_at,
            a.updated_by,
            a.created_at
        ],
    )?;
    Ok(())
}

pub fn set_attribute_deleted(conn: &Connection, attribute_id: &str, version: i64, now: i64, updated_by: Option<&str>) -> Result<usize> {
    Ok(conn.execute(
        "UPDATE attributes SET is_deleted = 1, version = ?1, updated_at = ?2, updated_by = ?3 WHERE attribute_id = ?4",
        rusqlite::params![version, now, updated_by, attribute_id],
    )?)
}

pub fn list_attributes_for_note(conn: &Connection, note_id: &str) -> Result<Vec<Attribute>> {
    let mut stmt = conn.prepare(
        "SELECT attribute_id, note_id, attr_type, name, value, is_inherited, is_deleted, version, updated_at, updated_by, created_at
         FROM attributes WHERE note_id = ?1 AND is_deleted = 0 ORDER BY created_at ASC",
    )?;
    let rows = stmt.query_map(rusqlite::params![note_id], row_to_attribute)?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

pub fn list_tag_names(conn: &Connection, note_id: &str) -> Result<Vec<String>> {
    let mut stmt = conn.prepare(
        "SELECT name FROM attributes WHERE note_id = ?1 AND attr_type = 'label' AND is_deleted = 0 ORDER BY name",
    )?;
    let rows = stmt.query_map(rusqlite::params![note_id], |r| r.get::<_, String>(0))?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

pub fn list_tags(conn: &Connection, note_id: Option<&str>) -> Result<Vec<Tag>> {
    let mut stmt = conn.prepare(
        "SELECT a.name, COUNT(*) AS note_count FROM attributes a
         WHERE a.attr_type = 'label' AND a.is_deleted = 0
           AND (?1 IS NULL OR a.note_id = ?1)
         GROUP BY a.name ORDER BY note_count DESC, a.name ASC",
    )?;
    let rows = stmt.query_map(rusqlite::params![note_id], |r| {
        Ok(Tag {
            name: r.get(0)?,
            note_count: r.get(1)?,
        })
    })?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

fn row_to_blob(r: &rusqlite::Row<'_>) -> rusqlite::Result<Blob> {
    Ok(Blob {
        blob_id: r.get(0)?,
        size: r.get(1)?,
        mime_type: r.get(2)?,
        storage_type: r.get(3)?,
        storage_path: r.get(4)?,
        created_at: r.get(5)?,
    })
}

pub fn insert_blob(conn: &Connection, b: &Blob) -> Result<()> {
    conn.execute(
        "INSERT OR IGNORE INTO blobs (blob_id, size, mime_type, storage_type, storage_path, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        rusqlite::params![b.blob_id, b.size, b.mime_type, b.storage_type, b.storage_path, b.created_at],
    )?;
    Ok(())
}

pub fn get_blob(conn: &Connection, blob_id: &str) -> Result<Option<Blob>> {
    let b = conn
        .query_row(
            "SELECT blob_id, size, mime_type, storage_type, storage_path, created_at FROM blobs WHERE blob_id = ?1",
            rusqlite::params![blob_id],
            row_to_blob,
        )
        .optional()?;
    Ok(b)
}

pub fn blob_exists(conn: &Connection, blob_id: &str) -> Result<bool> {
    Ok(get_blob(conn, blob_id)?.is_some())
}

pub fn list_notes_by_blob(conn: &Connection, blob_id: &str) -> Result<Vec<String>> {
    let mut stmt = conn.prepare("SELECT note_id FROM notes WHERE blob_id = ?1")?;
    let rows = stmt.query_map(rusqlite::params![blob_id], |r| r.get::<_, String>(0))?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

pub fn upsert_blob(conn: &Connection, b: &Blob) -> Result<()> {
    conn.execute(
        "INSERT INTO blobs (blob_id, size, mime_type, storage_type, storage_path, created_at)
         VALUES (?1,?2,?3,?4,?5,?6)
         ON CONFLICT(blob_id) DO UPDATE SET
            size = excluded.size,
            mime_type = excluded.mime_type,
            storage_type = excluded.storage_type,
            storage_path = excluded.storage_path",
        rusqlite::params![
            b.blob_id,
            b.size,
            b.mime_type,
            b.storage_type,
            b.storage_path,
            b.created_at
        ],
    )?;
    Ok(())
}
