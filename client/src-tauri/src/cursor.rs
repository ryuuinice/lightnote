use crate::error::Result;
use rusqlite::Connection;

pub fn get(conn: &Connection, client_id: &str, now: i64) -> Result<i64> {
    let seq = conn.query_row(
        "SELECT last_server_sequence FROM sync_state WHERE client_id = ?1",
        rusqlite::params![client_id],
        |r| r.get(0),
    );
    match seq {
        Ok(seq) => Ok(seq),
        Err(rusqlite::Error::QueryReturnedNoRows) => {
            conn.execute(
                "INSERT INTO sync_state (client_id, last_server_sequence, updated_at) VALUES (?1, 0, ?2)",
                rusqlite::params![client_id, now],
            )?;
            Ok(0)
        }
        Err(e) => Err(e.into()),
    }
}

/// cursor 只前进、不回退：新值必须大于现有值才更新
pub fn set(conn: &Connection, client_id: &str, seq: i64, now: i64) -> Result<()> {
    conn.execute(
        "INSERT INTO sync_state (client_id, last_server_sequence, updated_at) VALUES (?1, ?2, ?3)
         ON CONFLICT(client_id) DO UPDATE SET
            last_server_sequence = MAX(last_server_sequence, excluded.last_server_sequence),
            updated_at = excluded.updated_at",
        rusqlite::params![client_id, seq, now],
    )?;
    Ok(())
}
