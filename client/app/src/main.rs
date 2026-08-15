#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod auth;

use auth::{parse_login_response, parse_refresh_response, FileCredentialStore, SessionMeta, TokenStore};
use lightnote_core::blob::UreqBlobTransport;
use lightnote_core::commands::Core;
use lightnote_core::engine::SyncEngine;
use lightnote_core::models::{Attribute, ConflictInfo, Note, NoteMeta, SearchResult, SyncStatus};
use lightnote_core::sync::UreqTransport;
use lightnote_core::util::now_ms;
use serde::Serialize;
use std::path::PathBuf;
use std::sync::Mutex;
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

fn with_core<T>(state: &State<AppState>, f: impl FnOnce(&mut Core) -> lightnote_core::Result<T>) -> lightnote_core::Result<T> {
    let mut guard = state
        .core
        .lock()
        .expect("core lock poisoned");
    let core = guard
        .as_mut()
        .ok_or_else(|| lightnote_core::Error::InvalidArgument("core not initialized".into()))?;
    f(core)
}

fn ensure_connected(state: &State<AppState>) -> lightnote_core::Result<()> {
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

fn run_sync(state: &State<AppState>) -> lightnote_core::Result<lightnote_core::engine::SyncReport> {
    ensure_connected(state)?;
    let engine_guard = state.engine.lock().expect("engine lock");
    let engine = engine_guard.as_ref().expect("engine initialized");
    let blob_guard = state.blob_transport.lock().expect("blob lock");
    let blob = blob_guard.as_ref().expect("blob initialized");
    with_core(state, |core| core.sync_trigger_with_blob(engine, blob))
}

/// 令 access_token 作废后必须重建 engine/blob transport（它们持有旧 token）。
fn rebuild_transports(state: &State<AppState>) {
    *state.engine.lock().expect("engine lock") = None;
    *state.blob_transport.lock().expect("blob lock") = None;
}

/// 清空全部会话状态（access+refresh+元信息），回到登录页。refresh 失败 / logout 调用。
fn clear_session(state: &State<AppState>) {
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
}

/// 用持久化的 refresh_token 换新 access_token（轮换：同时存新 refresh_token）。
/// 持锁串行化：并发触发时后者等到前者完成后复查 token 有效性，直接复用，
/// 避免两个并发 refresh 消费同一 refresh_token（第二次 401 → 误清有效会话）。
/// 400/401/403（无效/吊销）→ 清会话；网络错误/5xx → 不清（瞬时）。
fn do_refresh(state: &State<AppState>) -> Result<(), String> {
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
        Ok(r) if r.status() == 200 => {
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
        Ok(r) => {
            let code = r.status();
            // 400 INVALID_REFRESH_TOKEN / 401 / 403 DEVICE_REVOKED：会话不可恢复
            if code == 400 || code == 401 || code == 403 {
                clear_session(state);
            }
            // 5xx 等其他状态视为瞬时错误：不清会话
            Err(format!("refresh failed: {code}"))
        }
        Err(e) => Err(e.to_string()), // 网络瞬时错误：不清会话
    }
}

/// 调任何需要 server 的命令前确保 access_token 有效；过期则自动 refresh。
fn ensure_valid_token(state: &State<AppState>) -> Result<(), String> {
    let now = now_ms();
    let expiry = *state.token_expiry.lock().expect("expiry lock");
    let has_token = !state.token.lock().expect("token lock").is_empty();
    if has_token && expiry > now {
        return Ok(());
    }
    do_refresh(state)
}

// ---------------------------------------------------------------------------
// Auth / Settings
// ---------------------------------------------------------------------------

#[derive(Serialize, Clone)]
struct AuthStatus {
    has_session: bool, // 是否存在可恢复会话（有 refresh_token 且有 server_url）
    server_url: String,
    device_name: String,
}

#[tauri::command]
fn auth_status(state: State<AppState>) -> Result<AuthStatus, String> {
    let has_refresh = state
        .token_store
        .lock()
        .expect("ts lock")
        .as_ref()
        .map(|ts| ts.load_refresh().unwrap_or(None).is_some())
        .unwrap_or(false);
    let server_url = state.server_url.lock().expect("url lock").clone();
    let device_name = state.device_name.lock().expect("dev lock").clone();
    Ok(AuthStatus {
        has_session: has_refresh && !server_url.is_empty(),
        server_url,
        device_name,
    })
}

#[tauri::command]
fn auth_refresh(state: State<AppState>) -> Result<String, String> {
    do_refresh(&state).map(|_| "ok".to_string())
}

#[tauri::command]
fn auth_login(
    state: State<AppState>,
    server_url: String,
    username: String,
    password: String,
    device_name: String,
) -> Result<String, String> {
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
        .send_json(body)
        .map_err(|e| e.to_string())?;
    if resp.status() != 200 {
        return Err(format!("login failed: {}", resp.status()));
    }
    let v: serde_json::Value = resp.into_json().map_err(|e| e.to_string())?;
    let t = parse_login_response(&v)?;

    let dir = state.data_dir.lock().expect("dir lock").clone();
    let meta = SessionMeta {
        server_url: base.clone(),
        device_id: t.device_id.clone().unwrap_or_default(),
        device_name: device_name.clone(),
    };
    meta.save(&dir).map_err(|e| e.to_string())?;
    if let Some(rt) = &t.refresh_token {
        let ts = state.token_store.lock().expect("ts lock");
        if let Some(ts) = ts.as_ref() {
            ts.save_refresh(rt).map_err(|e| e.to_string())?;
        }
    }
    *state.server_url.lock().expect("url lock") = base.clone();
    *state.token.lock().expect("token lock") = t.access_token;
    *state.token_expiry.lock().expect("expiry lock") = now_ms() + t.expires_in * 1000;
    *state.device_id.lock().expect("dev id lock") = t.device_id.unwrap_or_default();
    *state.device_name.lock().expect("dev lock") = device_name;
    let mut settings = state.settings.lock().expect("settings lock");
    settings.server_url = base;
    rebuild_transports(&state);
    Ok("ok".to_string())
}

#[tauri::command]
fn settings_get(state: State<AppState>) -> Result<AppSettings, String> {
    Ok(state.settings.lock().expect("settings lock").clone())
}

#[tauri::command]
fn settings_update(state: State<AppState>, server_url: Option<String>, auto_sync: Option<bool>, sync_interval_sec: Option<u64>) -> Result<AppSettings, String> {
    let mut s = state.settings.lock().expect("settings lock");
    if let Some(u) = server_url {
        s.server_url = u.trim_end_matches('/').to_string();
        *state.server_url.lock().expect("url lock") = s.server_url.clone();
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
fn notes_list(state: State<AppState>, parent_note_id: Option<String>, include_deleted: Option<bool>) -> Result<Vec<NoteMeta>, String> {
    with_core(&state, |c| {
        c.list_notes(parent_note_id.as_deref(), include_deleted.unwrap_or(false))
    })
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn notes_get(state: State<AppState>, note_id: String) -> Result<Note, String> {
    with_core(&state, |c| c.get_note(&note_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn notes_create(state: State<AppState>, parent_note_id: String, title: String, note_type: Option<String>) -> Result<NoteMeta, String> {
    with_core(&state, |c| c.create_note(&parent_note_id, &title, note_type.as_deref().unwrap_or("text")))
        .map_err(|e| e.to_string())
}

#[tauri::command]
fn notes_update(state: State<AppState>, note_id: String, title: Option<String>) -> Result<NoteMeta, String> {
    with_core(&state, |c| {
        let t = title.unwrap_or_else(|| c.get_note(&note_id).map(|n| n.title).unwrap_or_default());
        c.update_note(&note_id, &t)
    })
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn notes_delete(state: State<AppState>, note_id: String) -> Result<(), String> {
    with_core(&state, |c| c.delete_note(&note_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn notes_save_content(state: State<AppState>, note_id: String, content: String) -> Result<String, String> {
    with_core(&state, |c| c.save_content(&note_id, &content)).map_err(|e| e.to_string())
}

#[derive(serde::Serialize)]
struct NoteContent {
    blob_id: Option<String>,
    content: Option<String>,
}

#[tauri::command]
fn notes_get_content(state: State<AppState>, note_id: String) -> Result<NoteContent, String> {
    let (blob_id, content) = with_core(&state, |c| c.get_content(&note_id)).map_err(|e| e.to_string())?;
    Ok(NoteContent { blob_id, content })
}

#[tauri::command]
fn notes_restore(state: State<AppState>, note_id: String) -> Result<NoteMeta, String> {
    with_core(&state, |c| c.restore_note(&note_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn notes_attach(state: State<AppState>, parent_note_id: String, name: String, mime_type: String, data: Vec<u8>) -> Result<NoteMeta, String> {
    with_core(&state, |c| c.attach_bytes(&parent_note_id, &name, &mime_type, &data)).map_err(|e| e.to_string())
}

// ---------------------------------------------------------------------------
// Tree / Search / Tags / Trash / Conflicts
// ---------------------------------------------------------------------------

#[tauri::command]
fn tree_children(state: State<AppState>, parent_note_id: String) -> Result<Vec<NoteMeta>, String> {
    with_core(&state, |c| c.tree_children(&parent_note_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn tree_move(state: State<AppState>, note_id: String, new_parent_note_id: String, new_sort_order: Option<i64>) -> Result<(), String> {
    with_core(&state, |c| c.move_note_to(&note_id, &new_parent_note_id, new_sort_order)).map_err(|e| e.to_string())
}

#[tauri::command]
fn search_query(state: State<AppState>, query: String, limit: Option<usize>) -> Result<Vec<SearchResult>, String> {
    with_core(&state, |c| c.search(&query, limit.unwrap_or(20))).map_err(|e| e.to_string())
}

#[tauri::command]
fn tags_list(state: State<AppState>, note_id: Option<String>) -> Result<Vec<lightnote_core::models::Tag>, String> {
    with_core(&state, |c| c.tags_list(note_id.as_deref())).map_err(|e| e.to_string())
}

#[tauri::command]
fn tags_add(state: State<AppState>, note_id: String, name: String, value: Option<String>) -> Result<Attribute, String> {
    with_core(&state, |c| c.tags_add(&note_id, &name, value.as_deref())).map_err(|e| e.to_string())
}

#[tauri::command]
fn tags_remove(state: State<AppState>, attribute_id: String) -> Result<(), String> {
    with_core(&state, |c| c.tags_remove(&attribute_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn trash_list(state: State<AppState>) -> Result<Vec<NoteMeta>, String> {
    with_core(&state, |c| c.trash_list()).map_err(|e| e.to_string())
}

#[tauri::command]
fn conflicts_list(state: State<AppState>) -> Result<Vec<ConflictInfo>, String> {
    with_core(&state, |c| c.conflicts_list()).map_err(|e| e.to_string())
}

#[tauri::command]
fn conflicts_resolve(state: State<AppState>, conflict_note_id: String, action: String) -> Result<(), String> {
    let keep = match action.as_str() {
        "keep_conflict" => true,
        "discard_conflict" => false,
        other => return Err(format!("invalid action: {other}")),
    };
    with_core(&state, |c| c.conflicts_resolve(&conflict_note_id, keep)).map_err(|e| e.to_string())
}

#[tauri::command]
fn trash_empty(state: State<AppState>) -> Result<i64, String> {
    with_core(&state, |c| c.trash_empty()).map_err(|e| e.to_string())
}

#[tauri::command]
fn blobs_get(state: State<AppState>, blob_id: String) -> Result<Vec<u8>, String> {
    with_core(&state, |c| c.blobs_get(&blob_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn blobs_exists(state: State<AppState>, blob_id: String) -> Result<bool, String> {
    with_core(&state, |c| c.blobs_exists(&blob_id)).map_err(|e| e.to_string())
}

#[tauri::command]
fn settings_logout(state: State<AppState>) -> Result<(), String> {
    clear_session(&state);
    Ok(())
}

#[tauri::command]
fn devices_list(state: State<AppState>) -> Result<Vec<serde_json::Value>, String> {
    ensure_valid_token(&state)?;
    let url = state.server_url.lock().expect("url lock").clone();
    let token = state.token.lock().expect("token lock").clone();
    if url.is_empty() || token.is_empty() {
        return Ok(vec![]);
    }
    let resp = ureq::get(&format!("{url}/api/v1/devices"))
        .set("Authorization", &format!("Bearer {token}"))
        .timeout(Duration::from_secs(10))
        .call()
        .map_err(|e| e.to_string())?;
    let v: serde_json::Value = resp.into_json().map_err(|e| e.to_string())?;
    Ok(v["devices"].as_array().cloned().unwrap_or_default())
}

#[tauri::command]
fn devices_revoke(state: State<AppState>, device_id: String) -> Result<(), String> {
    ensure_valid_token(&state)?;
    let url = state.server_url.lock().expect("url lock").clone();
    let token = state.token.lock().expect("token lock").clone();
    let resp = ureq::delete(&format!("{url}/api/v1/devices/{device_id}"))
        .set("Authorization", &format!("Bearer {token}"))
        .timeout(Duration::from_secs(10))
        .call()
        .map_err(|e| e.to_string())?;
    if resp.status() != 200 {
        return Err(format!("revoke failed: {}", resp.status()));
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Sync
// ---------------------------------------------------------------------------

#[tauri::command]
fn sync_status(state: State<AppState>) -> Result<SyncStatus, String> {
    let mut status = with_core(&state, |c| c.sync_status()).map_err(|e| e.to_string())?;
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
fn sync_trigger(state: State<AppState>) -> Result<String, String> {
    ensure_valid_token(&state)?;
    run_sync(&state).map(|r| format!("pushed={} pulled={} cursor={}", r.pushed, r.pulled, r.cursor))
        .map_err(|e| e.to_string())
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
    };

    tauri::Builder::default()
        .manage(app_state)
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
            if let Some(meta) = SessionMeta::load(&dir) {
                *state.server_url.lock().expect("url lock") = meta.server_url.clone();
                *state.device_id.lock().expect("dev id lock") = meta.device_id;
                *state.device_name.lock().expect("dev lock") = meta.device_name;
                let mut s = state.settings.lock().expect("settings lock");
                s.server_url = meta.server_url;
            }
            let mut guard = state.core.lock().expect("core lock poisoned");
            *guard = Some(
                Core::open(
                    &db_path,
                    &blobs_path,
                    format!("client-{:?}", std::process::id()),
                    format!("device-{:?}", std::process::id()),
                )
                .expect("open core"),
            );
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
