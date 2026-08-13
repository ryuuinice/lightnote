use crate::error::Result;
use crate::fts;
use crate::util::now_ms;
use rusqlite::Connection;

const V1_COMMON: &str = include_str!("../../../docs/schema/common.sql");
const V1_CLIENT: &str = include_str!("../../../docs/schema/client.sql");

const V2_FTS_TRIGRAM: &str = "
DROP TABLE IF EXISTS note_fts;
CREATE VIRTUAL TABLE note_fts USING fts5(
    note_id UNINDEXED,
    title,
    content,
    tags,
    tokenize = 'trigram'
);
";

const V3_FTS_UNICODE61: &str = "
DROP TABLE IF EXISTS note_fts;
CREATE VIRTUAL TABLE note_fts USING fts5(
    note_id UNINDEXED,
    title,
    content,
    tags,
    tokenize = 'unicode61'
);
";

// V4：FTS 行键改用 notes.rowid（sync_note 按 rowid 删除/插入，O(log n)）。
// schema 形态不变（note_id 仍 UNINDEXED，仅供 search 投影读取），但旧行的自动 rowid
// 不等于 notes.rowid，故 DROP + CREATE + 全量重建，让每行 rowid = notes.rowid。
const V4_FTS_ROWID_KEYED: &str = "
DROP TABLE IF EXISTS note_fts;
CREATE VIRTUAL TABLE note_fts USING fts5(
    note_id UNINDEXED,
    title,
    content,
    tags,
    tokenize = 'unicode61'
);
";

fn migration_sql(version: i64) -> String {
    match version {
        1 => format!("{V1_COMMON}\n{V1_CLIENT}"),
        2 => V2_FTS_TRIGRAM.to_string(),
        3 => V3_FTS_UNICODE61.to_string(),
        4 => V4_FTS_ROWID_KEYED.to_string(),
        _ => unreachable!("unknown migration version {version}"),
    }
}

pub fn migrate(conn: &mut Connection) -> Result<()> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS schema_migrations (
            version    INTEGER PRIMARY KEY,
            applied_at INTEGER NOT NULL
        );",
    )?;
    let current: i64 = conn.query_row(
        "SELECT COALESCE(MAX(version), 0) FROM schema_migrations",
        [],
        |r| r.get(0),
    )?;
    let mut applied_v4 = false;
    for version in (current + 1)..=4 {
        let tx = conn.transaction()?;
        tx.execute_batch(&migration_sql(version))?;
        tx.execute(
            "INSERT INTO schema_migrations (version, applied_at) VALUES (?1, ?2)",
            rusqlite::params![version, now_ms()],
        )?;
        tx.commit()?;
        if version == 4 {
            applied_v4 = true;
        }
    }
    // V4 后重建 FTS（派生数据，可重建）：每行 rowid = notes.rowid
    if applied_v4 {
        fts::rebuild_all(conn)?;
    }
    Ok(())
}

pub fn current_version(conn: &Connection) -> Result<i64> {
    let v = conn.query_row(
        "SELECT COALESCE(MAX(version), 0) FROM schema_migrations",
        [],
        |r| r.get(0),
    )?;
    Ok(v)
}
