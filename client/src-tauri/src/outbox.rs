use crate::error::Result;
use rusqlite::{Connection, OptionalExtension};

/// SENDING 状态超时阈值：进程崩溃后超过该时长恢复为 PENDING
pub const SENDING_TIMEOUT_MS: i64 = 5 * 60 * 1000;

/// INVALID 结果不无限重试，压入 24h 后（避免死循环，等人工处理）
pub const INVALID_HOLD_MS: i64 = 24 * 3600 * 1000;

/// 指数退避序列（毫秒）：1s, 2s, 4s, 8s, 16s, 30s, 60s 封顶
const BACKOFF_MS: [i64; 7] = [1000, 2000, 4000, 8000, 16000, 30000, 60000];

pub fn backoff_ms(retry_count: i64) -> i64 {
    let idx = (retry_count - 1).max(0) as usize;
    BACKOFF_MS[idx.min(BACKOFF_MS.len() - 1)]
}

pub fn enqueue(conn: &Connection, change_id: &str, now: i64) -> Result<()> {
    conn.execute(
        "INSERT OR IGNORE INTO sync_outbox (change_id, state, retry_count, next_retry_at, last_error, created_at, updated_at)
         VALUES (?1, 'PENDING', 0, 0, NULL, ?2, ?2)",
        rusqlite::params![change_id, now],
    )?;
    Ok(())
}

/// 取出待发送批次：PENDING 且 next_retry_at 已到期
pub fn dequeue_batch(conn: &Connection, now: i64, batch_size: usize) -> Result<Vec<String>> {
    let mut stmt = conn.prepare(
        "SELECT o.change_id FROM sync_outbox o
         JOIN entity_changes c ON c.change_id = o.change_id
         WHERE o.state = 'PENDING' AND o.next_retry_at <= ?1
         ORDER BY o.created_at ASC LIMIT ?2",
    )?;
    let rows = stmt.query_map(rusqlite::params![now, batch_size as i64], |r| r.get::<_, String>(0))?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

pub fn mark_sending(conn: &Connection, change_id: &str, now: i64) -> Result<()> {
    conn.execute(
        "UPDATE sync_outbox SET state = 'SENDING', updated_at = ?1 WHERE change_id = ?2",
        rusqlite::params![now, change_id],
    )?;
    Ok(())
}

pub fn remove(conn: &Connection, change_id: &str) -> Result<()> {
    conn.execute("DELETE FROM sync_outbox WHERE change_id = ?1", rusqlite::params![change_id])?;
    Ok(())
}

/// 发送失败：恢复 PENDING 并按退避排期
pub fn mark_error(conn: &Connection, change_id: &str, error: &str, now: i64) -> Result<()> {
    let retry_count: i64 = conn
        .query_row(
            "SELECT retry_count FROM sync_outbox WHERE change_id = ?1",
            rusqlite::params![change_id],
            |r| r.get(0),
        )
        .optional()?
        .unwrap_or(0);
    let new_count = retry_count + 1;
    conn.execute(
        "UPDATE sync_outbox SET state = 'PENDING', retry_count = ?1, next_retry_at = ?2, last_error = ?3, updated_at = ?4 WHERE change_id = ?5",
        rusqlite::params![new_count, now + backoff_ms(new_count), error, now, change_id],
    )?;
    Ok(())
}

/// 不可重试错误（INVALID）：记录错误，推迟到指定时间，避免无限重试
pub fn mark_error_hold(conn: &Connection, change_id: &str, error: &str, hold_ms: i64, now: i64) -> Result<()> {
    let retry_count: i64 = conn
        .query_row(
            "SELECT retry_count FROM sync_outbox WHERE change_id = ?1",
            rusqlite::params![change_id],
            |r| r.get(0),
        )
        .optional()?
        .unwrap_or(0);
    conn.execute(
        "UPDATE sync_outbox SET state = 'PENDING', retry_count = ?1, next_retry_at = ?2, last_error = ?3, updated_at = ?4 WHERE change_id = ?5",
        rusqlite::params![retry_count + 1, now + hold_ms, error, now, change_id],
    )?;
    Ok(())
}

/// 崩溃恢复：SENDING 超时 → PENDING
pub fn recover_stale_sending(conn: &Connection, now: i64) -> Result<usize> {
    let n = conn.execute(
        "UPDATE sync_outbox SET state = 'PENDING', next_retry_at = 0, updated_at = ?1
         WHERE state = 'SENDING' AND updated_at < ?2",
        rusqlite::params![now, now - SENDING_TIMEOUT_MS],
    )?;
    Ok(n)
}

pub fn pending_count(conn: &Connection) -> Result<i64> {
    let n = conn.query_row(
        "SELECT COUNT(*) FROM sync_outbox WHERE state = 'PENDING' AND next_retry_at <= ?1",
        rusqlite::params![crate::util::now_ms()],
        |r| r.get(0),
    )?;
    Ok(n)
}

pub fn outbox_count(conn: &Connection) -> Result<i64> {
    let n = conn.query_row("SELECT COUNT(*) FROM sync_outbox", [], |r| r.get(0))?;
    Ok(n)
}

/// 重试中 / 失败未发送（retry_count > 0）的条目数
pub fn failed_count(conn: &Connection) -> Result<i64> {
    let n = conn.query_row(
        "SELECT COUNT(*) FROM sync_outbox WHERE retry_count > 0 AND state = 'PENDING'",
        [],
        |r| r.get(0),
    )?;
    Ok(n)
}
