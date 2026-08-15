use crate::blob::manager::BlobManager;
use crate::blob::BlobTransport;
use crate::error::Result;
use crate::outbox::backoff_ms;
use rusqlite::{Connection, OptionalExtension};

pub const MAX_RETRIES: i64 = 5;
pub const QUEUE_BATCH_LIMIT: usize = 50;
pub const DOWNLOADING_TIMEOUT_MS: i64 = 5 * 60 * 1000;

#[derive(Debug, Clone, Default, PartialEq)]
pub struct DownloadReport {
    pub downloaded: usize,
    pub skipped: usize,
    pub failed: usize,
    pub remaining: i64,
}

pub struct DownloadQueue {
    manager: BlobManager,
}

impl DownloadQueue {
    pub fn new(blob_dir: impl AsRef<std::path::Path>) -> Self {
        DownloadQueue {
            manager: BlobManager::new(blob_dir),
        }
    }

    pub fn manager(&self) -> &BlobManager {
        &self.manager
    }

    pub fn run(&self, conn: &Connection, transport: &dyn BlobTransport, now: i64) -> Result<DownloadReport> {
        let mut report = DownloadReport::default();
        loop {
            let ids = pickup(conn, now, QUEUE_BATCH_LIMIT)?;
            if ids.is_empty() {
                break;
            }
            for blob_id in ids {
                if self.manager.has_local(&blob_id) {
                    remove(conn, &blob_id)?;
                    report.skipped += 1;
                    continue;
                }
                mark_downloading(conn, &blob_id, now)?;
                let outcome = transport
                    .download(&blob_id)
                    .and_then(|bytes| self.manager.write_local_atomic(&blob_id, &bytes));
                match outcome {
                    Ok(()) => {
                        backfill_after_download(conn, &self.manager, &blob_id)?;
                        remove(conn, &blob_id)?;
                        report.downloaded += 1;
                    }
                    Err(e) => {
                        mark_failed(conn, &blob_id, &e.to_string(), now)?;
                        report.failed += 1;
                    }
                }
            }
        }
        report.remaining = pending_count(conn)?;
        Ok(report)
    }

    pub fn enqueue_pending(&self, conn: &Connection, now: i64) -> Result<usize> {
        let mut stmt = conn.prepare(
            "SELECT b.blob_id,
                    CASE WHEN EXISTS (
                        SELECT 1 FROM notes n WHERE n.blob_id = b.blob_id AND n.is_deleted = 0
                    ) THEN 1 ELSE 0 END
             FROM blobs b",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))
        })?;
        let mut enqueued = 0usize;
        for row in rows {
            let (blob_id, priority) = row?;
            if self.manager.has_local(&blob_id) {
                continue;
            }
            enqueue(conn, &blob_id, priority, now)?;
            enqueued += 1;
        }
        Ok(enqueued)
    }
}

/// blob 落盘后：回填 blobs.storage_path 为本地路径，并对引用该 blob 的笔记重建 FTS
/// （pull 时 storage_path 为空串，FTS 只索引了标题/标签；下载完成后需补全正文索引）
pub fn backfill_after_download(
    conn: &Connection,
    manager: &BlobManager,
    blob_id: &str,
) -> Result<()> {
    let local = manager.local_path(blob_id).to_string_lossy().into_owned();
    conn.execute(
        "UPDATE blobs SET storage_path = ?1 WHERE blob_id = ?2",
        rusqlite::params![local, blob_id],
    )?;
    for note_id in crate::repo::list_notes_by_blob(conn, blob_id)? {
        crate::fts::sync_note(conn, &note_id)?;
    }
    Ok(())
}

pub fn enqueue(conn: &Connection, blob_id: &str, priority: i64, now: i64) -> Result<()> {
    conn.execute(
        "INSERT INTO blob_download_queue (blob_id, priority, state, retry_count, next_retry_at, last_error, created_at, updated_at)
         VALUES (?1, ?2, 'PENDING', 0, 0, NULL, ?3, ?3)
         ON CONFLICT(blob_id) DO UPDATE SET
            state = 'PENDING',
            retry_count = 0,
            next_retry_at = 0,
            priority = MAX(priority, excluded.priority),
            last_error = NULL,
            updated_at = ?3
         WHERE blob_download_queue.state = 'FAILED'",
        rusqlite::params![blob_id, priority, now],
    )?;
    Ok(())
}

pub fn pickup(conn: &Connection, now: i64, limit: usize) -> Result<Vec<String>> {
    conn.execute(
        "UPDATE blob_download_queue SET state = 'PENDING', next_retry_at = 0, updated_at = ?1
         WHERE state = 'DOWNLOADING' AND updated_at < ?2",
        rusqlite::params![now, now - DOWNLOADING_TIMEOUT_MS],
    )?;
    let mut stmt = conn.prepare(
        "SELECT blob_id FROM blob_download_queue
         WHERE state = 'PENDING' AND next_retry_at <= ?1
         ORDER BY priority DESC, created_at ASC, blob_id ASC
         LIMIT ?2",
    )?;
    let rows = stmt.query_map(rusqlite::params![now, limit as i64], |r| r.get::<_, String>(0))?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

pub fn mark_downloading(conn: &Connection, blob_id: &str, now: i64) -> Result<()> {
    conn.execute(
        "UPDATE blob_download_queue SET state = 'DOWNLOADING', updated_at = ?1 WHERE blob_id = ?2",
        rusqlite::params![now, blob_id],
    )?;
    Ok(())
}

pub fn mark_failed(conn: &Connection, blob_id: &str, error: &str, now: i64) -> Result<()> {
    let retry_count: i64 = conn
        .query_row(
            "SELECT retry_count FROM blob_download_queue WHERE blob_id = ?1",
            rusqlite::params![blob_id],
            |r| r.get(0),
        )
        .optional()?
        .unwrap_or(0);
    let new_count = retry_count + 1;
    if new_count >= MAX_RETRIES {
        conn.execute(
            "UPDATE blob_download_queue SET state = 'FAILED', retry_count = ?1, last_error = ?2, updated_at = ?3 WHERE blob_id = ?4",
            rusqlite::params![new_count, error, now, blob_id],
        )?;
    } else {
        conn.execute(
            "UPDATE blob_download_queue SET state = 'PENDING', retry_count = ?1, next_retry_at = ?2, last_error = ?3, updated_at = ?4 WHERE blob_id = ?5",
            rusqlite::params![new_count, now + backoff_ms(new_count), error, now, blob_id],
        )?;
    }
    Ok(())
}

pub fn remove(conn: &Connection, blob_id: &str) -> Result<()> {
    conn.execute(
        "DELETE FROM blob_download_queue WHERE blob_id = ?1",
        rusqlite::params![blob_id],
    )?;
    Ok(())
}

pub fn pending_count(conn: &Connection) -> Result<i64> {
    let n = conn.query_row(
        "SELECT COUNT(*) FROM blob_download_queue WHERE state = 'PENDING' AND next_retry_at <= ?1",
        rusqlite::params![crate::util::now_ms()],
        |r| r.get(0),
    )?;
    Ok(n)
}

pub fn failed_count(conn: &Connection) -> Result<i64> {
    let n = conn.query_row(
        "SELECT COUNT(*) FROM blob_download_queue WHERE state = 'FAILED'",
        [],
        |r| r.get(0),
    )?;
    Ok(n)
}

pub fn queue_count(conn: &Connection) -> Result<i64> {
    let n = conn.query_row("SELECT COUNT(*) FROM blob_download_queue", [], |r| r.get(0))?;
    Ok(n)
}
