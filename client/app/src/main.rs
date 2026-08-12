#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use lightnote_core::blob::UreqBlobTransport;
use lightnote_core::commands::Core;
use lightnote_core::engine::SyncEngine;
use lightnote_core::models::{Attribute, ConflictInfo, Note, NoteMeta, SearchResult, SyncStatus};
use lightnote_core::sync::UreqTransport;
use serde::Serialize;
use std::sync::Mutex;
use tauri::{Manager, State};

struct AppState {
    core: Mutex<Option<Core>>,
    engine: Mutex<Option<SyncEngine>>,
    blob_transport: Mutex<Option<UreqBlobTransport>>,
    server_url: Mutex<String>,
    token: Mutex<String>,
    device_name: Mutex<String>,
    client_id: Mutex<String>,
    settings: Mutex<AppSettings>,
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

// ---------------------------------------------------------------------------
// Auth / Settings
// ---------------------------------------------------------------------------

#[tauri::command]
fn auth_login(
    state: State<AppState>,
    server_url: String,
    username: String,
    password: String,
    device_name: String,
) -> Result<String, String> {
    let url = format!("{}/api/v1/auth/login", server_url.trim_end_matches('/'));
    let body = serde_json::json!({
        "username": username,
        "password": password,
        "device_name": device_name,
        "device_type": "desktop",
    });
    let resp = ureq::post(&url)
        .timeout(std::time::Duration::from_secs(10))
        .send_json(body)
        .map_err(|e| e.to_string())?;
    if resp.status() != 200 {
        return Err(format!("login failed: {}", resp.status()));
    }
    let v: serde_json::Value = resp.into_json().map_err(|e| e.to_string())?;
    let token = v["access_token"].as_str().unwrap_or_default().to_string();
    if token.is_empty() {
        return Err("no access_token in response".to_string());
    }
    *state.server_url.lock().expect("url lock") = server_url.trim_end_matches('/').to_string();
    *state.token.lock().expect("token lock") = token;
    *state.device_name.lock().expect("dev lock") = device_name;
    let mut settings = state.settings.lock().expect("settings lock");
    settings.server_url = server_url.trim_end_matches('/').to_string();
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
    *state.token.lock().expect("token lock") = String::new();
    let mut engine = state.engine.lock().expect("engine lock");
    *engine = None;
    let mut blob = state.blob_transport.lock().expect("blob lock");
    *blob = None;
    Ok(())
}

#[tauri::command]
fn devices_list(state: State<AppState>) -> Result<Vec<serde_json::Value>, String> {
    let url = state.server_url.lock().expect("url lock").clone();
    let token = state.token.lock().expect("token lock").clone();
    if url.is_empty() || token.is_empty() {
        return Ok(vec![]);
    }
    let resp = ureq::get(&format!("{url}/api/v1/devices"))
        .set("Authorization", &format!("Bearer {token}"))
        .timeout(std::time::Duration::from_secs(10))
        .call()
        .map_err(|e| e.to_string())?;
    let v: serde_json::Value = resp.into_json().map_err(|e| e.to_string())?;
    Ok(v["devices"].as_array().cloned().unwrap_or_default())
}

#[tauri::command]
fn devices_revoke(state: State<AppState>, device_id: String) -> Result<(), String> {
    let url = state.server_url.lock().expect("url lock").clone();
    let token = state.token.lock().expect("token lock").clone();
    let resp = ureq::delete(&format!("{url}/api/v1/devices/{device_id}"))
        .set("Authorization", &format!("Bearer {token}"))
        .timeout(std::time::Duration::from_secs(10))
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
        device_name: Mutex::new(String::new()),
        client_id: Mutex::new(format!("client-{:?}", std::process::id())),
        settings: Mutex::new(AppSettings::default()),
    };

    tauri::Builder::default()
        .manage(app_state)
        .invoke_handler(tauri::generate_handler![
            auth_login,
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
            sync_status,
            sync_trigger,
        ])
        .setup(|app| {
            let dir = app.path().app_data_dir().unwrap_or_else(|_| {
                std::env::temp_dir().join("lightnote-data")
            });
            std::fs::create_dir_all(&dir).ok();
            let db_path = dir.join("lightnote.db");
            let blobs_path = dir.join("blobs");
            let state = app.state::<AppState>();
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
