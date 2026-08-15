//! Phase 9.2a — 客户端会话持久化与凭据存储（不依赖 Tauri，可单测）
//!
//! 设计要点：
//! - access_token 仅内存（AppState.token），**不**持久化。
//! - refresh_token 经 `TokenStore` 持久化，**不**写 SQLite（同步数据），**不**进 webview localStorage。
//! - 会话元信息（server_url / device_id / device_name）非敏感，存 `session.json`。
//! - 生产推荐 OS credential store（Windows Credential Manager / macOS Keychain / Linux Secret
//!   Service）。本模块先用「0600 权限凭据文件」后端（WSL 无 secret service 时也可用），
//!   `TokenStore` trait 便于后续替换为 keyring 实现。

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// 非敏感会话元信息（用于启动恢复：知道连哪个 server、哪个 device，再用 refresh_token 换 access_token）。
#[derive(Serialize, Deserialize, Clone, Default, PartialEq, Debug)]
pub struct SessionMeta {
    pub server_url: String,
    pub device_id: String,
    pub device_name: String,
}

impl SessionMeta {
    pub fn load(dir: &Path) -> Option<SessionMeta> {
        let s = fs::read_to_string(dir.join("session.json")).ok()?;
        serde_json::from_str(&s).ok()
    }
    pub fn save(&self, dir: &Path) -> Result<(), String> {
        fs::create_dir_all(dir).map_err(|e| e.to_string())?;
        let bytes = serde_json::to_vec(self).map_err(|e| e.to_string())?;
        // 原子写：tmp + rename
        let tmp = dir.join("session.json.tmp");
        fs::write(&tmp, bytes).map_err(|e| e.to_string())?;
        fs::rename(&tmp, dir.join("session.json")).map_err(|e| e.to_string())?;
        Ok(())
    }
    pub fn clear(dir: &Path) {
        let _ = fs::remove_file(dir.join("session.json"));
    }
}

/// refresh_token 持久化抽象。access_token 不经此存储。
pub trait TokenStore: Send + Sync {
    fn save_refresh(&self, token: &str) -> Result<(), String>;
    fn load_refresh(&self) -> Result<Option<String>, String>;
    fn clear_refresh(&self) -> Result<(), String>;
}

/// 0600 权限凭据文件后端（unix）。文件落在 app_data_dir/credential，独立于 SQLite 与 webview。
pub struct FileCredentialStore {
    path: PathBuf,
    _mu: Mutex<()>,
}

impl FileCredentialStore {
    pub fn new(dir: &Path) -> Self {
        Self { path: dir.join("credential"), _mu: Mutex::new(()) }
    }
}

impl TokenStore for FileCredentialStore {
    fn save_refresh(&self, token: &str) -> Result<(), String> {
        let _g = self._mu.lock().map_err(|e| e.to_string())?;
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let tmp = self.path.with_extension("tmp");
        fs::write(&tmp, token).map_err(|e| e.to_string())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&tmp, fs::Permissions::from_mode(0o600)).map_err(|e| e.to_string())?;
        }
        fs::rename(&tmp, &self.path).map_err(|e| e.to_string())?;
        Ok(())
    }
    fn load_refresh(&self) -> Result<Option<String>, String> {
        let _g = self._mu.lock().map_err(|e| e.to_string())?;
        match fs::read_to_string(&self.path) {
            Ok(s) if s.is_empty() => Ok(None),
            Ok(s) => Ok(Some(s)),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e.to_string()),
        }
    }
    fn clear_refresh(&self) -> Result<(), String> {
        let _g = self._mu.lock().map_err(|e| e.to_string())?;
        match fs::remove_file(&self.path) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e.to_string()),
        }
    }
}

/// /auth/login 响应解析
#[derive(Debug, PartialEq)]
pub struct AuthTokens {
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub device_id: Option<String>,
    pub expires_in: i64,
}

pub fn parse_login_response(v: &serde_json::Value) -> Result<AuthTokens, String> {
    let access_token = v["access_token"].as_str().filter(|s| !s.is_empty()).ok_or("no access_token")?.to_string();
    Ok(AuthTokens {
        access_token,
        refresh_token: v["refresh_token"].as_str().filter(|s| !s.is_empty()).map(String::from),
        device_id: v["device_id"].as_str().filter(|s| !s.is_empty()).map(String::from),
        expires_in: v["expires_in"].as_i64().unwrap_or(7200),
    })
}

/// /auth/refresh 响应解析（轮换后的新 access_token + 新 refresh_token）
pub fn parse_refresh_response(v: &serde_json::Value) -> Result<AuthTokens, String> {
    let access_token = v["access_token"].as_str().filter(|s| !s.is_empty()).ok_or("no access_token")?.to_string();
    Ok(AuthTokens {
        access_token,
        refresh_token: v["refresh_token"].as_str().filter(|s| !s.is_empty()).map(String::from),
        device_id: v["device_id"].as_str().filter(|s| !s.is_empty()).map(String::from),
        expires_in: v["expires_in"].as_i64().unwrap_or(7200),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp() -> tempfile::TempDir {
        tempfile::tempdir().expect("tmp")
    }

    #[test]
    fn session_meta_round_trip_and_clear() {
        let d = tmp();
        assert!(SessionMeta::load(d.path()).is_none());
        let m = SessionMeta { server_url: "http://x".into(), device_id: "dev-1".into(), device_name: "PC".into() };
        m.save(d.path()).unwrap();
        assert_eq!(SessionMeta::load(d.path()).as_ref(), Some(&m));
        SessionMeta::clear(d.path());
        assert!(SessionMeta::load(d.path()).is_none());
    }

    #[test]
    fn file_credential_store_round_trip() {
        let d = tmp();
        let s = FileCredentialStore::new(d.path());
        assert_eq!(s.load_refresh().unwrap(), None);
        s.save_refresh("rt-secret").unwrap();
        assert_eq!(s.load_refresh().unwrap().as_deref(), Some("rt-secret"));
        s.clear_refresh().unwrap();
        assert_eq!(s.load_refresh().unwrap(), None);
        // AUTH-07/08：凭据必须从磁盘物理删除，而非仅逻辑失效
        assert!(!d.path().join("credential").exists());
        // 幂等 clear
        s.clear_refresh().unwrap();
    }

    #[test]
    fn parse_login_extracts_all_fields() {
        let v = serde_json::json!({
            "access_token": "at", "refresh_token": "rt", "expires_in": 7200, "device_id": "dev"
        });
        let t = parse_login_response(&v).unwrap();
        assert_eq!(t.access_token, "at");
        assert_eq!(t.refresh_token.as_deref(), Some("rt"));
        assert_eq!(t.device_id.as_deref(), Some("dev"));
        assert_eq!(t.expires_in, 7200);
    }

    #[test]
    fn parse_login_missing_access_token_errors() {
        let v = serde_json::json!({ "refresh_token": "rt" });
        assert!(parse_login_response(&v).is_err());
    }

    #[test]
    fn parse_refresh_handles_rotation() {
        let v = serde_json::json!({ "access_token": "at2", "refresh_token": "rt2", "expires_in": 7200 });
        let t = parse_refresh_response(&v).unwrap();
        assert_eq!(t.access_token, "at2");
        assert_eq!(t.refresh_token.as_deref(), Some("rt2")); // 轮换后的新 token
        assert!(t.device_id.is_none());
    }

    #[test]
    fn empty_refresh_token_strings_are_ignored() {
        let v = serde_json::json!({ "access_token": "at", "refresh_token": "", "device_id": "" });
        let t = parse_login_response(&v).unwrap();
        assert!(t.refresh_token.is_none());
        assert!(t.device_id.is_none());
    }
}
