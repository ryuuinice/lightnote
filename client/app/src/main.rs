#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod auth;

use base64::Engine as _;

use auth::{parse_login_response, parse_refresh_response, FileCredentialStore, SessionMeta, TokenStore};
use lightnote_core::blob::UreqBlobTransport;
use lightnote_core::commands::Core;
use lightnote_core::engine::SyncEngine;
use lightnote_core::models::{Attribute, ConflictInfo, Note, NoteMeta, SearchResult, SyncStatus};
use lightnote_core::sync::UreqTransport;
use lightnote_core::util::now_ms;
use serde::Serialize;
use tauri::Emitter;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::{Manager, State};

struct AppState {
    core: Mutex<Option<Core>>,
    engine: Mutex<Option<SyncEngine>>,
    blob_transport: Mutex<Option<UreqBlobTransport>>,
    server_url: Mutex<String>,
    token: Mutex<String>,        // access_token，仅内存
    token_expiry: Mutex<i64>,    // access_token 到期 unix ms；0 = 未知/已过期
    device_id: Mutex<String>,
    device_name: Mutex<String>,
    client_id: Mutex<String>,
    settings: Mutex<AppSettings>,
    data_dir: Mutex<PathBuf>,    // app_data_dir；session.json / credential 落此
    token_store: Mutex<Option<Box<dyn TokenStore>>>, // 启动时在 setup 注入
    refresh_lock: Mutex<()>,     // 串行化并发 refresh（并发轮换会消费同一 token 导致伪登出）
    /// setup 注入：会话失效时向前端广播 session-cleared 事件
    app_handle: Mutex<Option<tauri::AppHandle>>,
}

#[derive(Serialize, Clone)]
struct AppSettings {
    server_url: String,
    auto_sync: bool,
    sync_interval_sec: u64,
}

impl Default for AppSettings {
    fn default() -> Self {
        AppSettings {
            server_url: String::new(),
            auto_sync: true,
            sync_interval_sec: 60,
        }
    }
}

fn with_core<T>(state: &AppState, f: impl FnOnce(&mut Core) -> lightnote_core::Result<T>) -> lightnote_core::Result<T> {
    let mut guard = state
        .core
        .lock()
        .expect("core lock poisoned");
    let core = guard
        .as_mut()
        .ok_or_else(|| lightnote_core::Error::InvalidArgument("core not initialized".into()))?;
    f(core)
}

/// 无 uuid crate 依赖的简易随机 hex（16 字节）：时间戳 + 进程 + 地址熵，仅用于 client_id 生成
fn uuid_v4_simple() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let mut buf = [0u8; 16];
    let t = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default();
    buf[..8].copy_from_slice(&t.as_nanos().to_le_bytes());
    let pid = std::process::id() as u64;
    let heap = &buf as *const _ as u64;
    buf[8..].copy_from_slice(&(pid ^ heap.rotate_left(17)).to_le_bytes());
    // 简单 xorshift 扩散
    let mut x = u64::from_le_bytes(buf[8..].try_into().unwrap()) | 1;
    for b in buf.iter_mut() {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        *b ^= x as u8;
    }
    buf.iter().map(|b| format!("{b:02x}")).collect()
}

fn ensure_connected(state: &AppState) -> lightnote_core::Result<()> {
    let url = state.server_url.lock().expect("url lock").clone();
    if url.is_empty() {
        return Err(lightnote_core::Error::InvalidArgument("server url not configured".into()));
    }
    let token = state.token.lock().expect("token lock").clone();
    let client_id = state.client_id.lock().expect("cid lock").clone();

    let mut engine = state.engine.lock().expect("engine lock");
    if engine.is_none() {
        let transport = UreqTransport::new(&url, &token)?;
        *engine = Some(SyncEngine::new(Box::new(transport), client_id));
    }
    let mut blob = state.blob_transport.lock().expect("blob lock");
    if blob.is_none() {
        *blob = Some(UreqBlobTransport::new(&url, &token)?);
    }
    Ok(())
}

fn run_sync(state: &AppState) -> lightnote_core::Result<lightnote_core::engine::SyncReport> {
    ensure_connected(state)?;
    let report = {
        let engine_guard = state.engine.lock().expect("engine lock");
        let engine = engine_guard.as_ref().expect("engine initialized");
        let blob_guard = state.blob_transport.lock().expect("blob lock");
        let blob = blob_guard.as_ref().expect("blob initialized");
        with_core(state, |core| core.sync_trigger_with_blob(engine, blob))
    };
    // 服务端可能提前吊销 access_token/设备（本地 expiry 未到、时钟偏差、服务端重启等）。
    // sync 传输层把 401/403 映射为 NotAuthenticated —— 此处强制 refresh（轮换新 token、
    // 重建 transport）后单次重试；refresh 判定 fatal 时 do_refresh 已清会话回登录页。
    if let Err(lightnote_core::Error::NotAuthenticated) = &report {
        do_refresh(state)
            .map_err(|e| lightnote_core::Error::Sync(format!("auth recovery failed: {e}")))?;
        ensure_connected(state)?;
        let engine_guard = state.engine.lock().expect("engine lock");
        let engine = engine_guard.as_ref().expect("engine initialized");
        let blob_guard = state.blob_transport.lock().expect("blob lock");
        let blob = blob_guard.as_ref().expect("blob initialized");
        return with_core(state, |core| core.sync_trigger_with_blob(engine, blob));
    }
    report
}

/// 令 access_token 作废后必须重建 engine/blob transport（它们持有旧 token）。
fn rebuild_transports(state: &AppState) {
    *state.engine.lock().expect("engine lock") = None;
    *state.blob_transport.lock().expect("blob lock") = None;
}

/// 清空全部会话状态（access+refresh+元信息），回到登录页。refresh 失败 / logout 调用。
/// 清理后广播 session-cleared：运行中的 UI 若停留在主界面须切回登录页，
/// 否则后续所有操作持续失败（设备吊销场景 AUTH-05）。
fn clear_session(state: &AppState) {
    let dir = state.data_dir.lock().expect("dir lock").clone();
    if let Some(ts) = state.token_store.lock().expect("ts lock").as_ref() {
        let _ = ts.clear_refresh();
    }
    SessionMeta::clear(&dir);
    *state.token.lock().expect("token lock") = String::new();
    *state.token_expiry.lock().expect("expiry lock") = 0;
    *state.device_id.lock().expect("dev id lock") = String::new();
    *state.server_url.lock().expect("url lock") = String::new();
    let mut s = state.settings.lock().expect("settings lock");
    s.server_url = String::new();
    rebuild_transports(state);
    if let Some(app_handle) = state.app_handle.lock().expect("app handle lock").as_ref() {
        let _ = app_handle.emit("session-cleared", ());
    }
}

/// 用持久化的 refresh_token 换新 access_token（轮换：同时存新 refresh_token）。
/// 持锁串行化：并发触发时后者等到前者完成后复查 token 有效性，直接复用，
/// 避免两个并发 refresh 消费同一 refresh_token（第二次 401 → 误清有效会话）。
/// 400/401/403（无效/吊销）→ 清会话；网络错误/5xx → 不清（瞬时）。
fn do_refresh(state: &AppState) -> Result<(), String> {
    let _serial = state.refresh_lock.lock().expect("refresh lock poisoned");
    // double-check：等锁期间可能已有并发调用完成刷新
    {
        let now = now_ms();
        let expiry = *state.token_expiry.lock().expect("expiry lock");
        let has_token = !state.token.lock().expect("token lock").is_empty();
        if has_token && expiry > now {
            return Ok(());
        }
    }
    let refresh_token = {
        let ts = state.token_store.lock().expect("ts lock");
        let ts = ts.as_ref().ok_or_else(|| "token store not ready".to_string())?;
        ts.load_refresh().map_err(|e| e.to_string())?
    };
    let Some(rt) = refresh_token else {
        clear_session(state);
        return Err("no refresh token".into());
    };
    let url = state.server_url.lock().expect("url lock").clone();
    let resp = ureq::post(&format!("{url}/api/v1/auth/refresh"))
        .timeout(Duration::from_secs(10))
        .send_json(serde_json::json!({ "refresh_token": rt }));
    match resp {
        Ok(r) => {
            let v: serde_json::Value = r.into_json().map_err(|e| e.to_string())?;
            let t = parse_refresh_response(&v)?;
            *state.token.lock().expect("token lock") = t.access_token;
            *state.token_expiry.lock().expect("expiry lock") = now_ms() + t.expires_in * 1000;
            if let Some(new_rt) = t.refresh_token {
                let ts = state.token_store.lock().expect("ts lock");
                if let Some(ts) = ts.as_ref() {
                    ts.save_refresh(&new_rt).map_err(|e| e.to_string())?;
                }
            }
            rebuild_transports(state);
            Ok(())
        }
        Err(ureq::Error::Status(code, _)) => {
            // 400 INVALID_REFRESH_TOKEN / 401 / 403 DEVICE_REVOKED：会话不可恢复，清本地凭据。
            // 注意 ureq 2.x 非 2xx 返回 Err(Status(..)) 而非 Ok(response)，须在此分支处理。
            if refresh_failure_is_fatal(code) {
                clear_session(state);
            }
            // 5xx 等其他状态视为瞬时错误：不清会话
            Err(format!("refresh failed: {code}"))
        }
        Err(e) => Err(e.to_string()), // 网络瞬时错误：不清会话
    }
}

/// 调任何需要 server 的命令前确保 access_token 有效；过期则自动 refresh。
fn ensure_valid_token(state: &AppState) -> Result<(), String> {
    let now = now_ms();
    let expiry = *state.token_expiry.lock().expect("expiry lock");
    let has_token = !state.token.lock().expect("token lock").is_empty();
    if has_token && expiry > now {
        return Ok(());
    }
    do_refresh(state)
}

/// refresh 失败是否不可恢复（须清除本地会话与凭据文件）。
/// 400 INVALID_REFRESH_TOKEN / 401 / 403 DEVICE_REVOKED = 不可恢复；
/// 5xx 与网络错误 = 瞬时，保留会话。AUTH-07/08 回归锚点。
fn refresh_failure_is_fatal(code: u16) -> bool {
    matches!(code, 400 | 401 | 403)
}

#[cfg(test)]
mod tests {
    use super::refresh_failure_is_fatal;

    #[test]
    fn fatal_refresh_failures_clear_session() {
        // 400/401/403 → 必须清会话（含磁盘凭据）
        assert!(refresh_failure_is_fatal(400));
        assert!(refresh_failure_is_fatal(401));
        assert!(refresh_failure_is_fatal(403));
    }

    #[test]
    fn transient_refresh_failures_keep_session() {
        // 5xx / 网络层错误 → 不清会话（避免瞬时故障导致伪登出）
        assert!(!refresh_failure_is_fatal(500));
        assert!(!refresh_failure_is_fatal(502));
        assert!(!refresh_failure_is_fatal(503));
        assert!(!refresh_failure_is_fatal(404));
    }
}

// ---------------------------------------------------------------------------
// Auth / Settings
// ---------------------------------------------------------------------------

#[derive(Serialize, Clone)]
struct AuthStatus {
    has_session: bool, // 是否存在可恢复会话（有 refresh_token 且有 server_url）
    server_url: String,
    device_id: String,
    device_name: String,
}

#[tauri::command]
fn auth_status(state: State<'_, Arc<AppState>>) -> Result<AuthStatus, String> {
    let has_refresh = state
        .token_store
        .lock()
        .expect("ts lock")
        .as_ref()
        .map(|ts| ts.load_refresh().unwrap_or(None).is_some())
        .unwrap_or(false);
    let server_url = state.server_url.lock().expect("url lock").clone();
    let device_id = state.device_id.lock().expect("dev id lock").clone();
    let device_name = state.device_name.lock().expect("dev lock").clone();
    Ok(AuthStatus {
        has_session: has_refresh && !server_url.is_empty(),
        server_url,
        device_id,
        device_name,
    })
}

#[tauri::command]
async fn auth_refresh(state: State<'_, Arc<AppState>>) -> Result<String, String> {
    let app = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || do_refresh(&app).map(|_| "ok".to_string()))
        .await
        .map_err(|e| format!("refresh task panicked: {e}"))?
}

#[tauri::command]
async fn auth_login(
    state: State<'_, Arc<AppState>>,
    server_url: String,
    username: String,
    password: String,
    device_name: String,
) -> Result<String, String> {
    let app = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<String, String> {
        let base = server_url.trim_end_matches('/').to_string();
        let url = format!("{base}/api/v1/auth/login");
        let body = serde_json::json!({
            "username": username,
            "password": password,
            "device_name": device_name,
            "device_type": "desktop",
        });
        let resp = ureq::post(&url)
            .timeout(Duration::from_secs(10))
            .send_json(body);
        let resp = match resp {
            Ok(r) => r,
            // ureq 2.x：非 2xx 走 Err(Status(..))；4xx 提取服务端错误消息，其余报状态码
            Err(ureq::Error::Status(code, r)) => {
                let msg = r
                    .into_json::<serde_json::Value>()
                    .ok()
                    .and_then(|v| v["message"].as_str().map(str::to_string))
                    .unwrap_or_else(|| format!("login failed: {code}"));
                return Err(msg);
            }
            Err(e) => return Err(e.to_string()),
        };
        let v: serde_json::Value = resp.into_json().map_err(|e| e.to_string())?;
        let t = parse_login_response(&v)?;

        let dir = app.data_dir.lock().expect("dir lock").clone();
        // client_id：沿用已有（session.json）或首登生成，保持同步游标跨重启稳定
        let client_id = {
            let existing = SessionMeta::load(&dir).map(|m| m.client_id).unwrap_or_default();
            if !existing.is_empty() {
                existing
            } else {
                format!("client-{}", uuid_v4_simple())
            }
        };
        let meta = SessionMeta {
            server_url: base.clone(),
            device_id: t.device_id.clone().unwrap_or_default(),
            device_name: device_name.clone(),
            client_id: client_id.clone(),
        };
        meta.save(&dir).map_err(|e| e.to_string())?;
        if let Some(rt) = &t.refresh_token {
            let ts = app.token_store.lock().expect("ts lock");
            if let Some(ts) = ts.as_ref() {
                ts.save_refresh(rt).map_err(|e| e.to_string())?;
            }
        }
        *app.server_url.lock().expect("url lock") = base.clone();
        *app.token.lock().expect("token lock") = t.access_token;
        *app.token_expiry.lock().expect("expiry lock") = now_ms() + t.expires_in * 1000;
        *app.device_id.lock().expect("dev id lock") = t.device_id.unwrap_or_default();
        *app.device_name.lock().expect("dev lock") = device_name;
        *app.client_id.lock().expect("client id lock") = client_id;
        let mut settings = app.settings.lock().expect("settings lock");
        settings.server_url = base;
        rebuild_transports(&app);
        Ok("ok".to_string())
    })
    .await
    .map_err(|e| format!("login task panicked: {e}"))?
}

#[tauri::command]
fn settings_get(state: State<'_, Arc<AppState>>) -> Result<AppSettings, String> {
    Ok(state.settings.lock().expect("settings lock").clone())
}

#[tauri::command]
fn settings_update(state: State<'_, Arc<AppState>>, server_url: Option<String>, auto_sync: Option<bool>, sync_interval_sec: Option<u64>) -> Result<AppSettings, String> {
    let mut s = state.settings.lock().expect("settings lock");
    if let Some(u) = server_url {
        let trimmed = u.trim_end_matches('/').to_string();
        let changed = trimmed != s.server_url;
        s.server_url = trimmed;
        *state.server_url.lock().expect("url lock") = s.server_url.clone();
        // 地址变更后旧 transport 仍连旧服务器：立即重建，下次 sync 即用新地址
        if changed {
            rebuild_transports(state.inner());
        }
    }
    if let Some(a) = auto_sync {
        s.auto_sync = a;
    }
    if let Some(i) = sync_interval_sec {
        s.sync_interval_sec = i;
    }
    Ok(s.clone())
}

// ---------------------------------------------------------------------------
// Notes
// ---------------------------------------------------------------------------

#[tauri::command]
fn notes_list(state: State<'_, Arc<AppState>>, parent_note_id: Option<String>, include_deleted: Option<bool>) -> Result<Vec<NoteMeta>, String> {
    with_core(state.inner(), |c| {
        c.list_notes(parent_note_id.as_deref(), include_deleted.unwrap_or(false))
    })
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn notes_get(state: State<'_, Arc<AppState>>, note_id: String) -> Result<Note, String> {
    with_core(state.inner(), |c| c.get_note(&note_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn notes_create(state: State<'_, Arc<AppState>>, parent_note_id: String, title: String, note_type: Option<String>) -> Result<NoteMeta, String> {
    with_core(state.inner(), |c| c.create_note(&parent_note_id, &title, note_type.as_deref().unwrap_or("text")))
        .map_err(|e| e.to_string())
}

#[tauri::command]
fn notes_update(state: State<'_, Arc<AppState>>, note_id: String, title: Option<String>) -> Result<NoteMeta, String> {
    with_core(state.inner(), |c| {
        let t = title.unwrap_or_else(|| c.get_note(&note_id).map(|n| n.title).unwrap_or_default());
        c.update_note(&note_id, &t)
    })
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn notes_delete(state: State<'_, Arc<AppState>>, note_id: String) -> Result<(), String> {
    with_core(state.inner(), |c| c.delete_note(&note_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn notes_save_content(state: State<'_, Arc<AppState>>, note_id: String, content: String) -> Result<String, String> {
    with_core(state.inner(), |c| c.save_content(&note_id, &content)).map_err(|e| e.to_string())
}

#[derive(serde::Serialize)]
struct NoteContent {
    blob_id: Option<String>,
    content: Option<String>,
}

#[tauri::command]
fn notes_get_content(state: State<'_, Arc<AppState>>, note_id: String) -> Result<NoteContent, String> {
    let (blob_id, content) = with_core(state.inner(), |c| c.get_content(&note_id)).map_err(|e| e.to_string())?;
    Ok(NoteContent { blob_id, content })
}

#[tauri::command]
fn notes_restore(state: State<'_, Arc<AppState>>, note_id: String) -> Result<NoteMeta, String> {
    with_core(state.inner(), |c| c.restore_note(&note_id)).map_err(|e| e.to_string())
}

/// 附件数据走 base64：JSON 数字数组传输会使体积膨胀 3-9 倍且序列化开销大
#[tauri::command]
fn notes_attach(state: State<'_, Arc<AppState>>, parent_note_id: String, name: String, mime_type: String, data_base64: String) -> Result<NoteMeta, String> {
    let data = base64::engine::general_purpose::STANDARD
        .decode(data_base64.as_bytes())
        .map_err(|e| format!("invalid base64 attachment: {e}"))?;
    with_core(state.inner(), |c| c.attach_bytes(&parent_note_id, &name, &mime_type, &data)).map_err(|e| e.to_string())
}

// ---------------------------------------------------------------------------
// Tree / Search / Tags / Trash / Conflicts
// ---------------------------------------------------------------------------

#[tauri::command]
fn tree_children(state: State<'_, Arc<AppState>>, parent_note_id: String) -> Result<Vec<NoteMeta>, String> {
    with_core(state.inner(), |c| c.tree_children(&parent_note_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn tree_move(state: State<'_, Arc<AppState>>, note_id: String, new_parent_note_id: String, new_sort_order: Option<i64>) -> Result<(), String> {
    with_core(state.inner(), |c| c.move_note_to(&note_id, &new_parent_note_id, new_sort_order)).map_err(|e| e.to_string())
}

#[tauri::command]
fn search_query(state: State<'_, Arc<AppState>>, query: String, limit: Option<usize>) -> Result<Vec<SearchResult>, String> {
    with_core(state.inner(), |c| c.search(&query, limit.unwrap_or(20))).map_err(|e| e.to_string())
}

#[tauri::command]
fn tags_list(state: State<'_, Arc<AppState>>, note_id: Option<String>) -> Result<Vec<lightnote_core::models::Tag>, String> {
    with_core(state.inner(), |c| c.tags_list(note_id.as_deref())).map_err(|e| e.to_string())
}

#[tauri::command]
fn tags_add(state: State<'_, Arc<AppState>>, note_id: String, name: String, value: Option<String>) -> Result<Attribute, String> {
    with_core(state.inner(), |c| c.tags_add(&note_id, &name, value.as_deref())).map_err(|e| e.to_string())
}

#[tauri::command]
fn tags_remove(state: State<'_, Arc<AppState>>, attribute_id: String) -> Result<(), String> {
    with_core(state.inner(), |c| c.tags_remove(&attribute_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn trash_list(state: State<'_, Arc<AppState>>) -> Result<Vec<NoteMeta>, String> {
    with_core(state.inner(), |c| c.trash_list()).map_err(|e| e.to_string())
}

#[tauri::command]
fn conflicts_list(state: State<'_, Arc<AppState>>) -> Result<Vec<ConflictInfo>, String> {
    with_core(state.inner(), |c| c.conflicts_list()).map_err(|e| e.to_string())
}

#[tauri::command]
fn conflicts_resolve(state: State<'_, Arc<AppState>>, conflict_note_id: String, action: String) -> Result<(), String> {
    let keep = match action.as_str() {
        "keep_conflict" => true,
        "discard_conflict" => false,
        other => return Err(format!("invalid action: {other}")),
    };
    with_core(state.inner(), |c| c.conflicts_resolve(&conflict_note_id, keep)).map_err(|e| e.to_string())
}

#[tauri::command]
fn trash_empty(state: State<'_, Arc<AppState>>) -> Result<i64, String> {
    with_core(state.inner(), |c| c.trash_empty()).map_err(|e| e.to_string())
}

#[tauri::command]
fn blobs_get(state: State<'_, Arc<AppState>>, blob_id: String) -> Result<Vec<u8>, String> {
    with_core(state.inner(), |c| c.blobs_get(&blob_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn blobs_exists(state: State<'_, Arc<AppState>>, blob_id: String) -> Result<bool, String> {
    with_core(state.inner(), |c| c.blobs_exists(&blob_id)).map_err(|e| e.to_string())
}

/// 按需下载单个 blob（Lazy Download 补拉：打开笔记但本地正文/附件缺失时）
#[tauri::command]
async fn blobs_download(state: State<'_, Arc<AppState>>, blob_id: String) -> Result<(), String> {
    let app = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<(), String> {
        ensure_connected(&app).map_err(|e| e.to_string())?;
        let blob_guard = app.blob_transport.lock().expect("blob lock");
        let blob = blob_guard.as_ref().ok_or_else(|| "blob transport not ready".to_string())?;
        with_core(&app, |c| c.blob_download(blob, &blob_id)).map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| format!("download task panicked: {e}"))?
}

#[tauri::command]
fn settings_logout(state: State<'_, Arc<AppState>>) -> Result<(), String> {
    clear_session(state.inner());
    Ok(())
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct DeviceEntry {
    device_id: String,
    device_name: String,
    device_type: Option<String>,
    last_seen: i64,
    revoked_at: Option<i64>,
    created_at: i64,
}

#[tauri::command]
async fn devices_list(state: State<'_, Arc<AppState>>) -> Result<Vec<DeviceEntry>, String> {
    let app = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<Vec<DeviceEntry>, String> {
        ensure_valid_token(&app)?;
        let url = app.server_url.lock().expect("url lock").clone();
        let token = app.token.lock().expect("token lock").clone();
        if url.is_empty() || token.is_empty() {
            return Ok(vec![]);
        }
        let resp = ureq::get(&format!("{url}/api/v1/devices"))
            .set("Authorization", &format!("Bearer {token}"))
            .timeout(Duration::from_secs(10))
            .call()
            .map_err(|e| e.to_string())?;
        let v: serde_json::Value = resp.into_json().map_err(|e| e.to_string())?;
        let Some(arr) = v["devices"].as_array() else {
            return Ok(vec![]);
        };
        // 服务端返回 snake_case，前端契约是 camelCase：此处统一转换
        let mut out = Vec::with_capacity(arr.len());
        for d in arr {
            out.push(DeviceEntry {
                device_id: d["device_id"].as_str().unwrap_or_default().to_string(),
                device_name: d["device_name"].as_str().unwrap_or_default().to_string(),
                device_type: d["device_type"].as_str().map(str::to_string),
                last_seen: d["last_seen"].as_i64().unwrap_or(0),
                revoked_at: d["revoked_at"].as_i64(),
                created_at: d["created_at"].as_i64().unwrap_or(0),
            });
        }
        Ok(out)
    })
    .await
    .map_err(|e| format!("devices task panicked: {e}"))?
}

#[tauri::command]
async fn devices_revoke(state: State<'_, Arc<AppState>>, device_id: String) -> Result<(), String> {
    let app = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<(), String> {
        ensure_valid_token(&app)?;
        let url = app.server_url.lock().expect("url lock").clone();
        let token = app.token.lock().expect("token lock").clone();
        let resp = ureq::delete(&format!("{url}/api/v1/devices/{device_id}"))
            .set("Authorization", &format!("Bearer {token}"))
            .timeout(Duration::from_secs(10))
            .call();
        match resp {
            Ok(_) => Ok(()),
            Err(ureq::Error::Status(code, r)) => {
                let msg = r
                    .into_json::<serde_json::Value>()
                    .ok()
                    .and_then(|v| v["message"].as_str().map(str::to_string))
                    .unwrap_or_else(|| format!("revoke failed: {code}"));
                Err(msg)
            }
            Err(e) => Err(e.to_string()),
        }
    })
    .await
    .map_err(|e| format!("revoke task panicked: {e}"))?
}

// ---------------------------------------------------------------------------
// Sync
// ---------------------------------------------------------------------------

#[tauri::command]
fn sync_status(state: State<'_, Arc<AppState>>) -> Result<SyncStatus, String> {
    let mut status = with_core(state.inner(), |c| c.sync_status()).map_err(|e| e.to_string())?;
    let engine_guard = state.engine.lock().expect("engine lock");
    if let Some(engine) = engine_guard.as_ref() {
        let es = engine.status();
        status.state = es.state;
        status.failed_count = if es.last_error.is_some() { 1 } else { 0 };
        if es.last_sync_at > 0 {
            status.last_sync_at = es.last_sync_at;
        }
    }
    Ok(status)
}

#[tauri::command]
async fn sync_trigger(state: State<'_, Arc<AppState>>) -> Result<serde_json::Value, String> {
    // 阻塞式 HTTP（ureq）+ 全量 push/pull 必须离开主线程，否则同步期间窗口事件循环冻结
    let app = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || -> Result<serde_json::Value, String> {
        ensure_valid_token(&app)?;
        let report = run_sync(&app).map_err(|e| e.to_string())?;
        serde_json::to_value(&report).map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| format!("sync task panicked: {e}"))?
}

fn main() {
    let app_state = AppState {
        core: Mutex::new(None),
        engine: Mutex::new(None),
        blob_transport: Mutex::new(None),
        server_url: Mutex::new(String::new()),
        token: Mutex::new(String::new()),
        token_expiry: Mutex::new(0),
        device_id: Mutex::new(String::new()),
        device_name: Mutex::new(String::new()),
        client_id: Mutex::new(format!("client-{:?}", std::process::id())),
        settings: Mutex::new(AppSettings::default()),
        data_dir: Mutex::new(std::env::temp_dir().join("lightnote-data")),
        token_store: Mutex::new(None),
        refresh_lock: Mutex::new(()),
        app_handle: Mutex::new(None),
    };

    tauri::Builder::default()
        .manage(Arc::new(app_state))
        .invoke_handler(tauri::generate_handler![
            auth_login,
            auth_status,
            auth_refresh,
            settings_get,
            settings_update,
            notes_list,
            notes_get,
            notes_create,
            notes_update,
            notes_delete,
            notes_save_content,
            notes_get_content,
            notes_restore,
            notes_attach,
            trash_empty,
            blobs_get,
            blobs_exists,
            blobs_download,
            settings_logout,
            devices_list,
            devices_revoke,
            tree_children,
            tree_move,
            search_query,
            tags_list,
            tags_add,
            tags_remove,
            trash_list,
            conflicts_list,
            conflicts_resolve,
            sync_status,
            sync_trigger,
        ])
        .on_window_event(|window, event| {
            // 关窗前给前端一次 flush 机会：emit 事件 → Vue saveNow（<0.8s 防抖窗内的输入）。
            // 前端 flush 完成后自行调用 window.destroy() 真正关闭（见 App.vue onCloseRequested）。
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.emit("close-requested", ());
            }
        })
        .setup(|app| {
            // 数据目录：优先 LIGHTNOTE_DATA_DIR 环境变量（真机双实例隔离用）；
            // 注意 Windows 下设置 %APPDATA% 无效——Tauri 走 SHGetKnownFolderPath，不读该环境变量
            let dir = std::env::var("LIGHTNOTE_DATA_DIR")
                .ok()
                .map(PathBuf::from)
                .or_else(|| app.path().app_data_dir().ok())
                .unwrap_or_else(|| std::env::temp_dir().join("lightnote-data"));
            std::fs::create_dir_all(&dir).ok();
            let db_path = dir.join("lightnote.db");
            let blobs_path = dir.join("blobs");
            let state = app.state::<AppState>();
            // 注入凭据存储 + 记录数据目录
            *state.data_dir.lock().expect("dir lock") = dir.clone();
            *state.token_store.lock().expect("ts lock") =
                Some(Box::new(FileCredentialStore::new(&dir)));
            // 启动恢复：把上次会话的元信息载入内存（access_token 留给 Vue 调 auth_refresh 换取）
            let mut client_id = String::new();
            let mut device_id = String::new();
            let mut meta_changed = false;
            let mut meta_value = SessionMeta::default();
            if let Some(meta) = SessionMeta::load(&dir) {
                *state.server_url.lock().expect("url lock") = meta.server_url.clone();
                *state.device_id.lock().expect("dev id lock") = meta.device_id.clone();
                *state.device_name.lock().expect("dev lock") = meta.device_name.clone();
                let mut s = state.settings.lock().expect("settings lock");
                s.server_url = meta.server_url.clone();
                client_id = meta.client_id.clone();
                device_id = meta.device_id.clone();
                meta_value = meta;
            }
            // client_id 游标归属需跨启动稳定；旧版本 session.json 无此字段时
            // 生成后立即回写，避免每次启动生成新 ID → 游标反复从 0 全量重拉
            if client_id.is_empty() {
                client_id = format!("client-{}", uuid_v4_simple());
                meta_value.client_id = client_id.clone();
                meta_changed = true;
            }
            if device_id.is_empty() {
                device_id = format!("device-{}", uuid_v4_simple());
            }
            if meta_changed {
                let _ = meta_value.save(&dir);
            }
            // 注入 AppHandle：clear_session 等运行时路径可向前端广播事件
            *state.app_handle.lock().expect("app handle lock") = Some(app.handle().clone());
            *state.client_id.lock().expect("client id lock") = client_id.clone();
            let mut guard = state.core.lock().expect("core lock poisoned");
            *guard = Some(
                Core::open(
                    &db_path,
                    &blobs_path,
                    client_id,
                    device_id,
                )
                .expect("open core"),
            );
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
