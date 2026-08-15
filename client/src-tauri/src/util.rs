use sha2::{Digest, Sha256};
use std::time::{SystemTime, UNIX_EPOCH};

/// Unix 毫秒时间戳（schema 约定）
pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system clock before unix epoch")
        .as_millis() as i64
}

/// 生成 UUIDv7（TEXT 主键 / change_id 约定）
pub fn uuid_v7() -> String {
    uuid::Uuid::now_v7().to_string()
}

pub fn sha256_hex(data: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(data);
    h.finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// 内容寻址 blob_id：'sha256:' || hex(SHA-256(content))
pub fn blob_id_of(content: &[u8]) -> String {
    format!("sha256:{}", sha256_hex(content))
}

/// 校验 blob_id 格式（防止服务端恶意值被拼入本地文件路径造成路径穿越）
pub fn valid_blob_id(blob_id: &str) -> bool {
    match blob_id.strip_prefix("sha256:") {
        Some(hex) => hex.len() == 64 && hex.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f')),
        None => false,
    }
}

/// 实体快照的 content_hash：'sha256:' || hex(SHA-256(规范化 JSON 文本))
pub fn snapshot_hash(payload: &serde_json::Value) -> String {
    format!("sha256:{}", sha256_hex(payload.to_string().as_bytes()))
}

pub fn is_cjk(c: char) -> bool {
    matches!(c, '\u{4E00}'..='\u{9FFF}' | '\u{3400}'..='\u{4DBF}' | '\u{F900}'..='\u{FAFF}' | '\u{3040}'..='\u{30FF}' | '\u{AC00}'..='\u{D7AF}')
}
