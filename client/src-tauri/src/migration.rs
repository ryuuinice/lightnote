use crate::error::Result;
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

fn migration_sql(version: i64) -> String {
    match version {
        1 => format!("{V1_COMMON}\n{V1_CLIENT}"),
        2 => V2_FTS_TRIGRAM.to_string(),
        3 => V3_FTS_UNICODE61.to_string(),
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
    for version in (current + 1)..=3 {
        let tx = conn.transaction()?;
        tx.execute_batch(&migration_sql(version))?;
        tx.execute(
            "INSERT INTO schema_migrations (version, applied_at) VALUES (?1, ?2)",
            rusqlite::params![version, now_ms()],
        )?;
        tx.commit()?;
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
