use crate::error::{Error, Result};
use crate::models::{EntityType, Operation};
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const PUSH_BATCH_LIMIT: usize = 1000;
pub const PULL_BATCH_LIMIT: u32 = 500;

#[derive(Debug, Clone, Serialize)]
pub struct PushChange {
    pub change_id: String,
    pub origin_device_id: String,
    pub entity_type: EntityType,
    pub entity_id: String,
    pub operation: Operation,
    pub base_version: i64,
    pub version: i64,
    pub content_hash: Option<String>,
    pub payload: Value,
}

impl PushChange {
    pub fn wire(&self) -> Value {
        serde_json::json!({
            "change_id": self.change_id,
            "origin_device_id": self.origin_device_id,
            "entity_type": self.entity_type.as_str(),
            "entity_id": self.entity_id,
            "operation": self.operation.as_str(),
            "base_version": self.base_version,
            "version": self.version,
            "content_hash": self.content_hash,
            "payload": self.payload,
        })
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct PushResult {
    pub change_id: String,
    pub status: String,
    pub server_sequence: Option<i64>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct PushResponse {
    pub results: Vec<PushResult>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct PullChange {
    pub server_sequence: i64,
    pub change_id: String,
    pub origin_device_id: Option<String>,
    pub entity_type: String,
    pub entity_id: String,
    pub operation: String,
    pub version: i64,
    pub payload: Value,
}

#[derive(Debug, Clone, Deserialize)]
pub struct PullResponse {
    pub changes: Vec<PullChange>,
    pub next_sequence: i64,
    pub has_more: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PushStatus {
    Applied,
    AlreadyApplied,
    Conflict,
    Invalid,
    Other,
}

impl PushStatus {
    pub fn parse(s: &str) -> PushStatus {
        match s {
            "APPLIED" => PushStatus::Applied,
            "ALREADY_APPLIED" => PushStatus::AlreadyApplied,
            "CONFLICT" => PushStatus::Conflict,
            "INVALID" => PushStatus::Invalid,
            _ => PushStatus::Other,
        }
    }
}

pub trait SyncTransport: Send + Sync {
    fn push_changes(&self, changes: &[PushChange]) -> Result<PushResponse>;
    fn pull_changes(&self, after: i64, limit: u32) -> Result<PullResponse>;
}

impl<T: SyncTransport + ?Sized> SyncTransport for std::sync::Arc<T> {
    fn push_changes(&self, changes: &[PushChange]) -> Result<PushResponse> {
        self.as_ref().push_changes(changes)
    }

    fn pull_changes(&self, after: i64, limit: u32) -> Result<PullResponse> {
        self.as_ref().pull_changes(after, limit)
    }
}

/// ureq 阻塞 HTTP 实现；Phase 5 接 Tauri 时可替换为 reqwest 异步实现
pub struct UreqTransport {
    agent: ureq::Agent,
    base_url: String,
    token: String,
}

impl UreqTransport {
    pub fn new(base_url: &str, token: &str) -> Result<Self> {
        if base_url.is_empty() {
            return Err(Error::InvalidArgument("base_url is empty".into()));
        }
        let agent = ureq::AgentBuilder::new()
            .timeout(std::time::Duration::from_secs(30))
            .build();
        Ok(UreqTransport {
            agent,
            base_url: base_url.trim_end_matches('/').to_string(),
            token: token.to_string(),
        })
    }
}

impl SyncTransport for UreqTransport {
    fn push_changes(&self, changes: &[PushChange]) -> Result<PushResponse> {
        let url = format!("{}/api/v1/sync/push", self.base_url);
        let body = serde_json::json!({
            "changes": changes.iter().map(|c| c.wire()).collect::<Vec<_>>()
        });
        let resp = self
            .agent
            .post(&url)
            .set("Authorization", &format!("Bearer {}", self.token))
            .send_json(body)?;
        if resp.status() != 200 {
            return Err(Error::Sync(format!("push http {}", resp.status())));
        }
        let parsed: PushResponse = resp.into_json()?;
        Ok(parsed)
    }

    fn pull_changes(&self, after: i64, limit: u32) -> Result<PullResponse> {
        let url = format!(
            "{}/api/v1/sync/changes?after={}&limit={}",
            self.base_url, after, limit
        );
        let resp = self
            .agent
            .get(&url)
            .set("Authorization", &format!("Bearer {}", self.token))
            .call()?;
        if resp.status() != 200 {
            return Err(Error::Sync(format!("pull http {}", resp.status())));
        }
        let parsed: PullResponse = resp.into_json()?;
        Ok(parsed)
    }
}
