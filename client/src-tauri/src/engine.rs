use crate::apply;
use crate::change;
use crate::cursor;
use crate::db::Db;
use crate::error::Result;
use crate::outbox;
use crate::sync::{PushChange, PushStatus, SyncTransport, PUSH_BATCH_LIMIT, PULL_BATCH_LIMIT};
use crate::util::now_ms;
use std::collections::HashSet;
use std::sync::Mutex;

#[derive(Debug, Clone, Default, PartialEq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncReport {
    pub blob_queued: usize,
    pub pushed: usize,
    pub pulled: usize,
    pub invalid: usize,
    pub cursor: i64,
    pub pending_remaining: i64,
    /// blob 上传失败数（本地有、服务端缺，上传出错）
    pub blob_upload_failed: usize,
    /// blob 下载失败数（懒下载队列执行失败）
    pub blob_download_failed: usize,
    /// blob 下载成功数（懒下载补全；前端据此决定是否刷新当前笔记内容）
    pub blob_downloaded: usize,
}

#[derive(Debug, Clone, PartialEq)]
pub struct EngineStatus {
    pub state: String,
    pub last_sync_at: i64,
    pub last_error: Option<String>,
}

impl Default for EngineStatus {
    fn default() -> Self {
        EngineStatus {
            state: "idle".to_string(),
            last_sync_at: 0,
            last_error: None,
        }
    }
}

pub struct SyncEngine {
    transport: Box<dyn SyncTransport>,
    client_id: String,
    status: Mutex<EngineStatus>,
}

impl SyncEngine {
    pub fn new(transport: Box<dyn SyncTransport>, client_id: impl Into<String>) -> Self {
        SyncEngine {
            transport,
            client_id: client_id.into(),
            status: Mutex::new(EngineStatus::default()),
        }
    }

    pub fn status(&self) -> EngineStatus {
        self.status.lock().expect("engine status poisoned").clone()
    }

    fn set_state(&self, state: &str) {
        if let Ok(mut s) = self.status.lock() {
            s.state = state.to_string();
        }
    }

    /// 触发一次同步：Push First, Pull Second；失败不影响本地编辑
    pub fn sync_once(&self, db: &mut Db) -> Result<SyncReport> {
        self.set_state("preparing");
        let result = self.run(db);
        match result {
            Ok(report) => {
                if let Ok(mut s) = self.status.lock() {
                    s.state = "completed".to_string();
                    s.last_sync_at = now_ms();
                    s.last_error = None;
                }
                Ok(report)
            }
            Err(e) => {
                if let Ok(mut s) = self.status.lock() {
                    s.state = "error".to_string();
                    s.last_error = Some(e.to_string());
                }
                Err(e)
            }
        }
    }

    fn run(&self, db: &mut Db) -> Result<SyncReport> {
        let mut report = SyncReport::default();
        self.set_state("pushing");
        outbox::recover_stale_sending(db.connection(), now_ms())?;
        let (pushed, invalid) = self.push_all(db)?;
        report.pushed = pushed;
        report.invalid = invalid;
        self.set_state("pulling");
        report.pulled = self.pull_all(db)?;
        report.cursor = cursor::get(db.connection(), &self.client_id, now_ms())?;
        report.pending_remaining = outbox::pending_count(db.connection())?;
        Ok(report)
    }

    fn push_all(&self, db: &mut Db) -> Result<(usize, usize)> {
        let mut total = 0usize;
        let mut invalid = 0usize;
        loop {
            let now = now_ms();
            let ids = outbox::dequeue_batch(db.connection(), now, PUSH_BATCH_LIMIT)?;
            if ids.is_empty() {
                break;
            }
            let changes = change::list_changes_by_ids(db.connection(), &ids)?;
            {
                let tx = db.tx()?;
                for id in &ids {
                    outbox::mark_sending(&tx, id, now)?;
                }
                tx.commit()?;
            }
            let push_changes: Vec<PushChange> = changes
                .into_iter()
                .map(|c| PushChange {
                    change_id: c.change_id,
                    origin_device_id: c.origin_device_id,
                    entity_type: c.entity_type,
                    entity_id: c.entity_id,
                    operation: c.operation,
                    base_version: c.base_version,
                    version: c.version,
                    content_hash: c.content_hash,
                    payload: c.payload,
                })
                .collect();
            match self.transport.push_changes(&push_changes) {
                Ok(resp) => {
                    let now = now_ms();
                    let tx = db.tx()?;
                    let mut handled = HashSet::new();
                    for r in resp.results {
                        handled.insert(r.change_id.clone());
                        match PushStatus::parse(&r.status) {
                            PushStatus::Applied | PushStatus::AlreadyApplied => {
                                if let Some(seq) = r.server_sequence {
                                    change::backfill_server_sequence(&tx, &r.change_id, seq)?;
                                }
                                outbox::remove(&tx, &r.change_id)?;
                                total += 1;
                            }
                            PushStatus::Conflict => {
                                outbox::remove(&tx, &r.change_id)?;
                                total += 1;
                            }
                            PushStatus::Invalid => {
                                outbox::mark_error_hold(
                                    &tx,
                                    &r.change_id,
                                    "INVALID",
                                    outbox::INVALID_HOLD_MS,
                                    now,
                                )?;
                                invalid += 1;
                            }
                            PushStatus::Other => {
                                outbox::mark_error_hold(
                                    &tx,
                                    &r.change_id,
                                    &format!("UNKNOWN_STATUS {}", r.status),
                                    outbox::INVALID_HOLD_MS,
                                    now,
                                )?;
                                invalid += 1;
                            }
                        }
                    }
                    for id in &ids {
                        if !handled.contains(id) {
                            outbox::mark_error(&tx, id, "no result for change", now)?;
                        }
                    }
                    tx.commit()?;
                }
                Err(e) => {
                    let now = now_ms();
                    let tx = db.tx()?;
                    for id in &ids {
                        outbox::mark_error(&tx, id, &e.to_string(), now)?;
                    }
                    tx.commit()?;
                    return Err(e);
                }
            }
        }
        Ok((total, invalid))
    }

    fn pull_all(&self, db: &mut Db) -> Result<usize> {
        let mut total = 0usize;
        loop {
            let n = self.pull_once(db)?;
            if n == 0 {
                break;
            }
            total += n;
        }
        Ok(total)
    }

    /// 拉取并应用单个批次；失败整体回滚，cursor 不推进
    pub fn pull_once(&self, db: &mut Db) -> Result<usize> {
        let after = cursor::get(db.connection(), &self.client_id, now_ms())?;
        let resp = self.transport.pull_changes(after, PULL_BATCH_LIMIT)?;
        if resp.changes.is_empty() {
            return Ok(0);
        }
        let now = now_ms();
        let tx = db.tx()?;
        for pc in &resp.changes {
            apply::apply(&tx, pc)?;
        }
        cursor::set(&tx, &self.client_id, resp.next_sequence, now)?;
        tx.commit()?;
        Ok(resp.changes.len())
    }
}
