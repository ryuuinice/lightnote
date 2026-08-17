use crate::apply;
use crate::blob::{BlobManager, BlobTransport, DownloadQueue, InitResult, UreqBlobTransport};
use crate::change;
use crate::commands::Core;
use crate::cursor;
use crate::db::Db;
use crate::engine::SyncEngine;
use crate::error::{Error, Result};
use crate::migration;
use crate::models::{EntityType, Note, Operation};
use crate::outbox;
use crate::repo;
use crate::sync::{PullChange, PullResponse, PushChange, PushResult, PushResponse, SyncTransport, UreqTransport};
use crate::util::{now_ms, uuid_v7};
use rusqlite::Connection;
use serde_json::json;
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

fn keep_tempdir(tag: &str) -> std::path::PathBuf {
    static KEEP: OnceLock<Mutex<Vec<tempfile::TempDir>>> = OnceLock::new();
    let dir = tempfile::tempdir().unwrap_or_else(|_| panic!("tempdir {tag} failed"));
    let path = dir.path().to_path_buf();
    KEEP.get_or_init(Default::default).lock().unwrap().push(dir);
    path
}

fn memory_db() -> Db {
    Db::open_in_memory(keep_tempdir("db").join("blobs")).expect("open memory db")
}

fn core() -> Core {
    Core::open_in_memory(keep_tempdir("core").join("blobs"), "client-a", "device-a").expect("open core")
}

fn memory_conn() -> Connection {
    let mut conn = Connection::open_in_memory().expect("open conn");
    migration::migrate(&mut conn).expect("migrate");
    conn
}

fn test_table_exists(conn: &Connection, table: &str) -> bool {
    let n: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('table','view') AND name = ?1",
            rusqlite::params![table],
            |r| r.get(0),
        )
        .unwrap();
    n > 0
}

struct MockTransport {
    push_responses: Mutex<VecDeque<PushResponse>>,
    push_error: Mutex<Option<String>>,
    pull_responses: Mutex<VecDeque<PullResponse>>,
    push_calls: AtomicUsize,
    pull_calls: AtomicUsize,
}

impl MockTransport {
    fn new() -> Self {
        MockTransport {
            push_responses: Mutex::new(VecDeque::new()),
            push_error: Mutex::new(None),
            pull_responses: Mutex::new(VecDeque::new()),
            push_calls: AtomicUsize::new(0),
            pull_calls: AtomicUsize::new(0),
        }
    }

    fn with_push_error(err: &str) -> Self {
        let t = MockTransport::new();
        *t.push_error.lock().unwrap() = Some(err.to_string());
        t
    }

    fn with_push_results(&self, results: Vec<(String, &str, Option<i64>)>) -> &Self {
        self.push_responses.lock().unwrap().push_back(PushResponse {
            results: results
                .into_iter()
                .map(|(id, status, seq)| PushResult {
                    change_id: id,
                    status: status.to_string(),
                    server_sequence: seq,
                })
                .collect(),
        });
        self
    }

    fn with_pull_batch(&self, changes: Vec<PullChange>, next_sequence: i64, has_more: bool) -> &Self {
        self.pull_responses.lock().unwrap().push_back(PullResponse {
            changes,
            next_sequence,
            has_more,
        });
        self
    }

    fn push_calls(&self) -> usize {
        self.push_calls.load(Ordering::SeqCst)
    }

    fn pull_calls(&self) -> usize {
        self.pull_calls.load(Ordering::SeqCst)
    }
}

impl SyncTransport for MockTransport {
    fn push_changes(&self, changes: &[PushChange]) -> Result<PushResponse> {
        self.push_calls.fetch_add(1, Ordering::SeqCst);
        if let Some(err) = self.push_error.lock().unwrap().as_ref() {
            return Err(Error::Sync(err.clone()));
        }
        if let Some(resp) = self.push_responses.lock().unwrap().pop_front() {
            return Ok(resp);
        }
        Ok(PushResponse {
            results: changes
                .iter()
                .map(|c| PushResult {
                    change_id: c.change_id.clone(),
                    status: "APPLIED".to_string(),
                    server_sequence: None,
                })
                .collect(),
        })
    }

    fn pull_changes(&self, after: i64, _limit: u32) -> Result<PullResponse> {
        self.pull_calls.fetch_add(1, Ordering::SeqCst);
        if let Some(resp) = self.pull_responses.lock().unwrap().pop_front() {
            return Ok(resp);
        }
        Ok(PullResponse {
            changes: vec![],
            next_sequence: after,
            has_more: false,
        })
    }
}

fn note_pull_change(seq: i64, change_id: &str, entity_id: &str, version: i64, title: &str, operation: &str) -> PullChange {
    PullChange {
        server_sequence: seq,
        change_id: change_id.to_string(),
        origin_device_id: Some("device-b".to_string()),
        entity_type: "note".to_string(),
        entity_id: entity_id.to_string(),
        operation: operation.to_string(),
        version,
        payload: json!({
            "note_id": entity_id,
            "title": title,
            "note_type": "text",
            "blob_id": null,
            "is_deleted": false,
            "conflict_of_note_id": null,
        }),
    }
}

// TASK-010/011: 初始化与迁移
#[test]
fn migration_creates_all_tables_and_is_idempotent() {
    let path = keep_tempdir("mig1").join("test.db");
    let blob_dir = keep_tempdir("mig1b").join("blobs");
    let db = Db::open(&path, &blob_dir).expect("open");
    for table in [
        "schema_migrations",
        "users",
        "notes",
        "branches",
        "attributes",
        "blobs",
        "entity_changes",
        "sync_outbox",
        "sync_state",
        "blob_download_queue",
        "note_fts",
    ] {
        assert!(test_table_exists(db.connection(), table), "table {table} missing");
    }
    assert_eq!(migration::current_version(db.connection()).unwrap(), 4);
    let db2 = Db::open(&path, &blob_dir).expect("reopen");
    assert_eq!(migration::current_version(db2.connection()).unwrap(), 4);
}

#[test]
fn migration_reapplies_after_partial_failure() {
    let mut conn = memory_conn();
    conn.execute_batch("DROP TABLE note_fts; DELETE FROM schema_migrations WHERE version >= 1;")
        .unwrap();
    migration::migrate(&mut conn).unwrap();
    assert!(test_table_exists(&conn, "note_fts"));
    assert_eq!(migration::current_version(&conn).unwrap(), 4);
}

#[test]
fn db_pragmas_enabled() {
    let db = memory_db();
    let fk: i64 = db.connection().query_row("PRAGMA foreign_keys", [], |r| r.get(0)).unwrap();
    assert_eq!(fk, 1);
}

// TASK-012: Repository CRUD
#[test]
fn repo_note_crud_and_tree() {
    let conn = memory_conn();
    let now = now_ms();
    let note = Note::new("n1".into(), "parent".into(), "text".into(), now);
    repo::insert_note(&conn, &note).unwrap();
    let branch = crate::models::Branch::new("b1".into(), "p1".into(), "n1".into(), 0, now);
    repo::insert_branch(&conn, &branch).unwrap();
    let root_note = Note::new("n0".into(), "orphan".into(), "text".into(), now);
    repo::insert_note(&conn, &root_note).unwrap();
    assert!(repo::get_note(&conn, "n1").unwrap().is_some());
    let children = repo::list_notes(&conn, Some("p1"), false).unwrap();
    assert_eq!(children.len(), 1);
    assert_eq!(children[0].note_id, "n1");
    let roots = repo::list_notes(&conn, None, false).unwrap();
    assert_eq!(roots.len(), 1);
    assert_eq!(roots[0].note_id, "n0");
    assert!(repo::list_notes(&conn, Some("missing"), false).unwrap().is_empty());
    repo::update_note_title(&conn, "n1", "new title", 2, now, Some("dev")).unwrap();
    let n = repo::get_note(&conn, "n1").unwrap().unwrap();
    assert_eq!(n.title, "new title");
    assert_eq!(n.version, 2);
    repo::set_note_deleted(&conn, "n1", true, 3, now, None).unwrap();
    assert!(repo::get_note(&conn, "n1").unwrap().unwrap().is_deleted);
    assert!(repo::list_notes(&conn, Some("p1"), false).unwrap().is_empty());
    assert_eq!(repo::list_trashed(&conn).unwrap().len(), 1);
    assert_eq!(repo::list_notes(&conn, Some("p1"), true).unwrap().len(), 1);
}

#[test]
fn repo_branch_attribute_blob_crud() {
    let conn = memory_conn();
    let now = now_ms();
    let branch = crate::models::Branch::new("b1".into(), "n1".into(), "n2".into(), 0, now);
    repo::insert_branch(&conn, &branch).unwrap();
    assert_eq!(repo::get_branch(&conn, "b1").unwrap().unwrap().parent_note_id, "n1");
    repo::move_branch(&conn, "b1", "n9", 5, 2, now, None).unwrap();
    assert_eq!(repo::get_branch(&conn, "b1").unwrap().unwrap().parent_note_id, "n9");
    repo::set_branch_deleted(&conn, "b1", 3, now, None).unwrap();
    assert!(repo::get_branch(&conn, "b1").unwrap().unwrap().is_deleted);

    let attr = crate::models::Attribute::new("a1".into(), "n1".into(), "label".into(), "java".into(), None, now);
    repo::insert_attribute(&conn, &attr).unwrap();
    assert_eq!(repo::list_tag_names(&conn, "n1").unwrap(), vec!["java".to_string()]);
    assert_eq!(repo::list_tags(&conn, None).unwrap()[0].note_count, 1);
    repo::set_attribute_deleted(&conn, "a1", 2, now, None).unwrap();
    assert!(repo::list_tag_names(&conn, "n1").unwrap().is_empty());

    let blob = crate::models::Blob {
        blob_id: "sha256:abc".to_string(),
        size: 3,
        mime_type: Some("text/markdown".into()),
        storage_type: "file".to_string(),
        storage_path: "/tmp/x".to_string(),
        created_at: now,
    };
    repo::upsert_blob(&conn, &blob).unwrap();
    assert!(repo::blob_exists(&conn, "sha256:abc").unwrap());
    let b2 = crate::models::Blob { size: 9, ..blob };
    repo::upsert_blob(&conn, &b2).unwrap();
    assert_eq!(repo::get_blob(&conn, "sha256:abc").unwrap().unwrap().size, 9);
}

// TASK-013/021: 本地修改事务原子性（Entity + Change + Outbox 同事务；回滚全部消失）
#[test]
fn local_modify_tx_is_atomic_and_rollback_clears_everything() {
    let mut db = memory_db();
    {
        let tx = db.tx().unwrap();
        let note = Note::new("n1".into(), "t".into(), "text".into(), now_ms());
        repo::insert_note(&tx, &note).unwrap();
        let c = change::record_change(&tx, &change::NewChange {
            origin_device_id: "dev-a",
            entity_type: EntityType::Note,
            entity_id: "n1",
            operation: Operation::Create,
            base_version: 0,
            version: 1,
            payload: &json!({"x": 1}),
        }).unwrap();
        outbox::enqueue(&tx, &c.change_id, now_ms()).unwrap();
        fts_sync(&tx, "n1");
        tx.commit().unwrap();
        assert!(repo::get_note(db.connection(), "n1").unwrap().is_some());
        assert!(change::change_exists(db.connection(), &c.change_id).unwrap());
        assert_eq!(outbox::pending_count(db.connection()).unwrap(), 1);
        let fts_rows: i64 = db.connection().query_row("SELECT count(*) FROM note_fts", [], |r| r.get(0)).unwrap();
        assert_eq!(fts_rows, 1);
    }
    let mut db2 = memory_db();
    {
        let tx = db2.tx().unwrap();
        let note = Note::new("n2".into(), "t".into(), "text".into(), now_ms());
        repo::insert_note(&tx, &note).unwrap();
        let c = change::record_change(&tx, &change::NewChange {
            origin_device_id: "dev-a",
            entity_type: EntityType::Note,
            entity_id: "n2",
            operation: Operation::Create,
            base_version: 0,
            version: 1,
            payload: &json!({"x": 1}),
        }).unwrap();
        outbox::enqueue(&tx, &c.change_id, now_ms()).unwrap();
        fts_sync(&tx, "n2");
        tx.rollback();
        assert!(repo::get_note(db2.connection(), "n2").unwrap().is_none());
        assert!(!change::change_exists(db2.connection(), &c.change_id).unwrap());
        assert_eq!(outbox::pending_count(db2.connection()).unwrap(), 0);
        let fts_rows: i64 = db2.connection().query_row("SELECT count(*) FROM note_fts", [], |r| r.get(0)).unwrap();
        assert_eq!(fts_rows, 0);
    }
}

fn fts_sync(conn: &Connection, note_id: &str) {
    crate::fts::sync_note(conn, note_id).unwrap();
}

// TASK-014: Change Log 结构与 UUIDv7
#[test]
fn change_log_uuidv7_and_fields() {
    let conn = memory_conn();
    let payload = json!({"note_id": "n1", "title": "Docker", "is_deleted": false});
    let c = change::record_change(&conn, &change::NewChange {
            origin_device_id: "dev-a",
            entity_type: EntityType::Note,
            entity_id: "n1",
            operation: Operation::Update,
            base_version: 3,
            version: 4,
            payload: &payload,
        }).unwrap();
    assert_eq!(c.change_id.len(), 36);
    assert_eq!(&c.change_id[14..15], "7");
    let stored = change::get_change(&conn, &c.change_id).unwrap().unwrap();
    assert_eq!(stored.origin_device_id, "dev-a");
    assert_eq!(stored.entity_type, EntityType::Note);
    assert_eq!(stored.entity_id, "n1");
    assert_eq!(stored.operation, Operation::Update);
    assert_eq!(stored.base_version, 3);
    assert_eq!(stored.version, 4);
    assert!(stored.content_hash.unwrap().starts_with("sha256:"));
    assert_eq!(stored.payload, payload);
    assert!(stored.created_at > 0);
    assert_ne!(uuid_v7(), uuid_v7());
}

// TASK-015: Outbox 状态机与崩溃恢复
#[test]
fn outbox_state_machine_and_recovery() {
    let mut conn = memory_conn();
    let now = now_ms();
    let tx = conn.transaction().unwrap();
    let c1 = change::record_change(&tx, &change::NewChange {
            origin_device_id: "d",
            entity_type: EntityType::Note,
            entity_id: "n1",
            operation: Operation::Create,
            base_version: 0,
            version: 1,
            payload: &json!({}),
        }).unwrap();
    outbox::enqueue(&tx, &c1.change_id, now).unwrap();
    tx.commit().unwrap();

    assert_eq!(outbox::dequeue_batch(&conn, now, 10).unwrap(), vec![c1.change_id.clone()]);
    outbox::mark_sending(&conn, &c1.change_id, now).unwrap();
    assert!(outbox::dequeue_batch(&conn, now, 10).unwrap().is_empty());

    outbox::mark_error(&conn, &c1.change_id, "network down", now).unwrap();
    let (state, retry_count, next_retry_at, updated_at): (String, i64, i64, i64) = conn
        .query_row(
            "SELECT state, retry_count, next_retry_at, updated_at FROM sync_outbox WHERE change_id = ?1",
            rusqlite::params![c1.change_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
        )
        .unwrap();
    assert_eq!(state, "PENDING");
    assert_eq!(retry_count, 1);
    assert_eq!(next_retry_at, updated_at + 1000);
    assert!(outbox::dequeue_batch(&conn, next_retry_at - 1, 10).unwrap().is_empty());
    assert_eq!(outbox::dequeue_batch(&conn, next_retry_at, 10).unwrap().len(), 1);
    outbox::remove(&conn, &c1.change_id).unwrap();
    assert_eq!(outbox::outbox_count(&conn).unwrap(), 0);
}

#[test]
fn outbox_sending_timeout_recovery() {
    let mut conn = memory_conn();
    let now = now_ms();
    let tx = conn.transaction().unwrap();
    let c1 = change::record_change(&tx, &change::NewChange {
            origin_device_id: "d",
            entity_type: EntityType::Note,
            entity_id: "n1",
            operation: Operation::Create,
            base_version: 0,
            version: 1,
            payload: &json!({}),
        }).unwrap();
    outbox::enqueue(&tx, &c1.change_id, now).unwrap();
    outbox::mark_sending(&tx, &c1.change_id, now).unwrap();
    tx.commit().unwrap();
    assert_eq!(outbox::recover_stale_sending(&conn, now + outbox::SENDING_TIMEOUT_MS - 1).unwrap(), 0);
    assert_eq!(outbox::recover_stale_sending(&conn, now + outbox::SENDING_TIMEOUT_MS + 1).unwrap(), 1);
    assert_eq!(outbox::dequeue_batch(&conn, now + outbox::SENDING_TIMEOUT_MS + 1, 10).unwrap().len(), 1);
}

#[test]
fn outbox_backoff_sequence() {
    assert_eq!(outbox::backoff_ms(1), 1000);
    assert_eq!(outbox::backoff_ms(2), 2000);
    assert_eq!(outbox::backoff_ms(4), 8000);
    assert_eq!(outbox::backoff_ms(6), 30000);
    assert_eq!(outbox::backoff_ms(10), 60000);
}

// TASK-016: Cursor
#[test]
fn cursor_read_write_forward_only() {
    let conn = memory_conn();
    let now = now_ms();
    assert_eq!(cursor::get(&conn, "c1", now).unwrap(), 0);
    cursor::set(&conn, "c1", 100, now).unwrap();
    cursor::set(&conn, "c1", 100, now).unwrap();
    assert_eq!(cursor::get(&conn, "c1", now).unwrap(), 100);
    cursor::set(&conn, "c1", 90, now).unwrap();
    assert_eq!(cursor::get(&conn, "c1", now).unwrap(), 100);
    cursor::set(&conn, "c1", 200, now).unwrap();
    assert_eq!(cursor::get(&conn, "c1", now).unwrap(), 200);
    assert_eq!(cursor::get(&conn, "c2", now).unwrap(), 0);
}

// TASK-018/021: Version Guard 三种分支
#[test]
fn version_guard_three_branches() {
    let conn = memory_conn();

    let pc = note_pull_change(1, "c1", "n1", 3, "hello", "CREATE");
    assert_eq!(apply::apply(&conn, &pc).unwrap(), apply::ApplyDecision::Apply);
    let note = repo::get_note(&conn, "n1").unwrap().unwrap();
    assert_eq!(note.title, "hello");
    assert_eq!(note.version, 3);
    let stored = change::get_change(&conn, "c1").unwrap().unwrap();
    assert_eq!(stored.server_sequence, Some(1));
    assert_eq!(stored.origin_device_id, "device-b");

    let pc_dup = note_pull_change(1, "c1", "n1", 3, "other", "UPDATE");
    assert_eq!(apply::apply(&conn, &pc_dup).unwrap(), apply::ApplyDecision::SkipDuplicate);
    assert_eq!(repo::get_note(&conn, "n1").unwrap().unwrap().title, "hello");

    let pc_old = note_pull_change(2, "c2", "n1", 2, "old", "UPDATE");
    assert_eq!(apply::apply(&conn, &pc_old).unwrap(), apply::ApplyDecision::SkipNewerLocal);
    assert_eq!(repo::get_note(&conn, "n1").unwrap().unwrap().title, "hello");
    assert_eq!(repo::get_note(&conn, "n1").unwrap().unwrap().version, 3);
    assert!(change::change_exists(&conn, "c2").unwrap());

    let pc_eq = note_pull_change(3, "c3", "n1", 3, "same version", "UPDATE");
    assert_eq!(apply::apply(&conn, &pc_eq).unwrap(), apply::ApplyDecision::Apply);
    assert_eq!(repo::get_note(&conn, "n1").unwrap().unwrap().title, "same version");
}

// TASK-017/021: Pull 应用不写 Outbox
#[test]
fn pull_apply_does_not_write_outbox() {
    let conn = memory_conn();
    let pc = note_pull_change(10, "c1", "n1", 1, "from server", "CREATE");
    apply::apply(&conn, &pc).unwrap();
    assert_eq!(outbox::outbox_count(&conn).unwrap(), 0);
    assert!(change::change_exists(&conn, "c1").unwrap());
    let stored = change::get_change(&conn, "c1").unwrap().unwrap();
    assert_eq!(stored.server_sequence, Some(10));
}

// TASK-017/021: 批量 Pull 事务回滚后 cursor 不推进
#[test]
fn batch_pull_rollback_does_not_advance_cursor() {
    let mut db = memory_db();
    let transport = MockTransport::new();
    transport.with_pull_batch(
        vec![
            note_pull_change(1, "c1", "n1", 1, "ok", "CREATE"),
            PullChange {
                entity_type: "nonexistent".to_string(),
                ..note_pull_change(2, "c2", "n2", 1, "bad", "CREATE")
            },
        ],
        2,
        false,
    );
    let engine = SyncEngine::new(Box::new(transport), "client-a");
    let err = engine.pull_once(&mut db);
    assert!(err.is_err());
    assert_eq!(cursor::get(db.connection(), "client-a", now_ms()).unwrap(), 0);
    assert!(repo::get_note(db.connection(), "n1").unwrap().is_none());
    assert!(!change::change_exists(db.connection(), "c1").unwrap());
}

// TASK-019/021: FTS 与实体同步更新
#[test]
fn fts_syncs_with_entity_and_search_works() {
    let mut core = core();
    let n1 = core.create_note("root", "Rust 并发编程", "text").unwrap();
    let n2 = core.create_note("root", "Docker 网络", "text").unwrap();
    let results = core.search("rust", 10).unwrap();
    assert_eq!(results.len(), 1);
    assert_eq!(results[0].note_id, n1.note_id);

    core.update_note(&n1.note_id, "Go 语言").unwrap();
    assert!(core.search("rust", 10).unwrap().is_empty());
    assert_eq!(core.search("Go 语言", 10).unwrap().len(), 1);

    core.tags_add(&n2.note_id, "java", None).unwrap();
    let results = core.search("java", 10).unwrap();
    assert_eq!(results.len(), 1);
    assert_eq!(results[0].matched_tags, vec!["java".to_string()]);

    core.delete_note(&n2.note_id).unwrap();
    assert!(core.search("java", 10).unwrap().is_empty());
    assert_eq!(core.fts_rebuild().unwrap(), 1);
    assert_eq!(core.search("go", 10).unwrap().len(), 1);
}

// TASK-017/021: Push 结果逐条处理（APPLIED 回填 sequence / CONFLICT / INVALID）
#[test]
fn push_result_handling_applied_conflict_invalid() {
    let mut db = memory_db();
    {
        let tx = db.tx().unwrap();
        let note = Note::new("n1".into(), "t".into(), "text".into(), now_ms());
        repo::insert_note(&tx, &note).unwrap();
        let c1 = change::record_change(&tx, &change::NewChange {
            origin_device_id: "dev-a",
            entity_type: EntityType::Note,
            entity_id: "n1",
            operation: Operation::Create,
            base_version: 0,
            version: 1,
            payload: &json!({}),
        }).unwrap();
        let c2 = change::record_change(&tx, &change::NewChange {
            origin_device_id: "dev-a",
            entity_type: EntityType::Note,
            entity_id: "n1",
            operation: Operation::Update,
            base_version: 1,
            version: 2,
            payload: &json!({}),
        }).unwrap();
        let c3 = change::record_change(&tx, &change::NewChange {
            origin_device_id: "dev-a",
            entity_type: EntityType::Note,
            entity_id: "n1",
            operation: Operation::Update,
            base_version: 2,
            version: 3,
            payload: &json!({}),
        }).unwrap();
        outbox::enqueue(&tx, &c1.change_id, now_ms()).unwrap();
        outbox::enqueue(&tx, &c2.change_id, now_ms()).unwrap();
        outbox::enqueue(&tx, &c3.change_id, now_ms()).unwrap();
        let ids = (c1.change_id.clone(), c2.change_id.clone(), c3.change_id.clone());
        tx.commit().unwrap();
        let transport = MockTransport::new();
        transport.with_push_results(vec![
            (ids.0.clone(), "APPLIED", Some(42)),
            (ids.1.clone(), "CONFLICT", None),
            (ids.2.clone(), "INVALID", None),
        ]);
        let engine = SyncEngine::new(Box::new(transport), "client-a");
        let report = engine.sync_once(&mut db).unwrap();
        assert_eq!(report.pushed, 2);
        assert_eq!(report.invalid, 1);
        assert_eq!(change::get_change(db.connection(), &ids.0).unwrap().unwrap().server_sequence, Some(42));
        let remaining: Vec<String> = {
            let mut stmt = db.connection().prepare("SELECT change_id FROM sync_outbox").unwrap();
            let rows = stmt.query_map([], |r| r.get::<_, String>(0)).unwrap();
            rows.collect::<rusqlite::Result<Vec<_>>>().unwrap()
        };
        assert_eq!(remaining, vec![ids.2.clone()]);
        let (state, next_retry_at, updated_at): (String, i64, i64) = db
            .connection()
            .query_row(
                "SELECT state, next_retry_at, updated_at FROM sync_outbox WHERE change_id = ?1",
                rusqlite::params![ids.2],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .unwrap();
        assert_eq!(state, "PENDING");
        assert_eq!(next_retry_at, updated_at + outbox::INVALID_HOLD_MS);
    }
}

// TASK-017/021: Push 网络错误 → Outbox 保留 + 退避；本地编辑不受影响
#[test]
fn push_network_error_keeps_outbox_and_local_data() {
    let mut core = core();
    core.create_note("root", "keep me", "text").unwrap();
    // v1.1.1 起根级笔记也建 root branch → 2 条 change（branch + note）
    assert_eq!(core.sync_status().unwrap().pending_count, 2);
    let transport = MockTransport::with_push_error("connection refused");
    let engine = SyncEngine::new(Box::new(transport), "client-a");
    let err = core.sync_trigger(&engine);
    assert!(err.is_err());
    let outbox_rows: i64 = core
        .db()
        .connection()
        .query_row("SELECT COUNT(*) FROM sync_outbox", [], |r| r.get(0))
        .unwrap();
    assert_eq!(outbox_rows, 2);
    let notes = core.list_notes(None, false).unwrap();
    assert_eq!(notes.len(), 1);
    assert_eq!(notes[0].title, "keep me");
    assert_eq!(engine.status().state, "error");
    let retry_count: i64 = core
        .db()
        .connection()
        .query_row("SELECT retry_count FROM sync_outbox LIMIT 1", [], |r| r.get(0))
        .unwrap();
    assert_eq!(retry_count, 1);
    let second = MockTransport::with_push_error("still down");
    let engine2 = SyncEngine::new(Box::new(second), "client-a");
    core.db()
        .connection()
        .execute("UPDATE sync_outbox SET next_retry_at = 0", [])
        .unwrap();
    assert!(core.sync_trigger(&engine2).is_err());
    let retry_count: i64 = core
        .db()
        .connection()
        .query_row("SELECT retry_count FROM sync_outbox LIMIT 1", [], |r| r.get(0))
        .unwrap();
    assert_eq!(retry_count, 2);
}

// TASK-017/021: 端到端 sync_once（push + pull）
#[test]
fn end_to_end_sync_once() {
    let mut db = memory_db();
    {
        let tx = db.tx().unwrap();
        let note = Note::new("n1".into(), "local".into(), "text".into(), now_ms());
        repo::insert_note(&tx, &note).unwrap();
        let c = change::record_change(&tx, &change::NewChange {
            origin_device_id: "dev-a",
            entity_type: EntityType::Note,
            entity_id: "n1",
            operation: Operation::Create,
            base_version: 0,
            version: 1,
            payload: &json!({}),
        }).unwrap();
        outbox::enqueue(&tx, &c.change_id, now_ms()).unwrap();
        tx.commit().unwrap();
    }
    let transport = Arc::new(MockTransport::new());
    transport.with_pull_batch(
        vec![
            note_pull_change(100, "r1", "n2", 1, "remote 1", "CREATE"),
            note_pull_change(101, "r2", "n3", 1, "remote 2", "CREATE"),
        ],
        101,
        false,
    );
    let engine = SyncEngine::new(Box::new(transport.clone()), "client-a");
    let report = engine.sync_once(&mut db).unwrap();
    assert_eq!(transport.push_calls(), 1);
    assert_eq!(transport.pull_calls(), 2);
    assert_eq!(report.pushed, 1);
    assert_eq!(report.pulled, 2);
    assert_eq!(report.cursor, 101);
    assert_eq!(outbox::pending_count(db.connection()).unwrap(), 0);
    assert!(repo::get_note(db.connection(), "n2").unwrap().is_some());
    assert!(repo::get_note(db.connection(), "n3").unwrap().is_some());
    assert_eq!(engine.status().state, "completed");
    assert!(engine.status().last_sync_at > 0);
}

// TASK-017/021: Pull 幂等重放：重复批次不重复应用，cursor 继续推进
#[test]
fn pull_idempotent_replay_advances_cursor() {
    let mut db = memory_db();
    let transport = MockTransport::new();
    transport.with_pull_batch(vec![note_pull_change(5, "x1", "n1", 1, "once", "CREATE")], 5, false);
    let engine = SyncEngine::new(Box::new(transport), "client-a");
    engine.pull_once(&mut db).unwrap();
    assert_eq!(cursor::get(db.connection(), "client-a", now_ms()).unwrap(), 5);
    let transport = MockTransport::new();
    transport.with_pull_batch(
        vec![
            note_pull_change(5, "x1", "n1", 1, "once", "CREATE"),
            note_pull_change(6, "x2", "n2", 1, "second", "CREATE"),
        ],
        6,
        false,
    );
    let engine = SyncEngine::new(Box::new(transport), "client-a");
    engine.pull_once(&mut db).unwrap();
    assert_eq!(repo::get_note(db.connection(), "n1").unwrap().unwrap().title, "once");
    assert_eq!(repo::get_note(db.connection(), "n2").unwrap().unwrap().title, "second");
    assert_eq!(cursor::get(db.connection(), "client-a", now_ms()).unwrap(), 6);
}

// TASK-020: Commands 端到端
#[test]
fn commands_end_to_end() {
    let mut core = core();
    let note = core.create_note("root", "Hello", "text").unwrap();
    assert_eq!(note.title, "Hello");
    let blob_id = core.save_content(&note.note_id, "# Hello\nworld").unwrap();
    assert!(blob_id.starts_with("sha256:"));
    let (blob_id2, content) = core.get_content(&note.note_id).unwrap();
    assert_eq!(blob_id2.as_deref(), Some(blob_id.as_str()));
    assert_eq!(content.as_deref(), Some("# Hello\nworld"));
    assert_eq!(core.tree_children("root").unwrap().len(), 1);

    let sub = core.create_note(&note.note_id, "Child", "text").unwrap();
    let branch_id: String = core
        .db()
        .connection()
        .query_row(
            "SELECT branch_id FROM branches WHERE child_note_id = ?1",
            rusqlite::params![sub.note_id],
            |r| r.get(0),
        )
        .unwrap();
    core.tree_move(&branch_id, "n9", Some(3)).unwrap();
    let moved = core.db().connection();
    let moved_parent: String = moved
        .query_row(
            "SELECT parent_note_id FROM branches WHERE branch_id = ?1",
            rusqlite::params![branch_id],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(moved_parent, "n9");

    core.delete_note(&sub.note_id).unwrap();
    assert_eq!(core.trash_list().unwrap().len(), 1);
    core.restore_note(&sub.note_id).unwrap();
    assert!(core.blobs_exists(&blob_id).unwrap());
    assert!(matches!(core.blobs_get("sha256:zzz"), Err(Error::BlobMissing(_))));
    let tags = core.tags_add(&note.note_id, "java", Some("1.8")).unwrap();
    assert_eq!(core.tags_list(None).unwrap().len(), 1);
    core.tags_remove(&tags.attribute_id).unwrap();
    assert!(core.tags_list(None).unwrap().is_empty());
    assert!(core.conflicts_list().unwrap().is_empty());
    let search = core.search("hello", 10).unwrap();
    assert_eq!(search.len(), 1);
}

// TASK-017: UreqTransport 与本地 mock HTTP server 集成
#[test]
fn ureq_transport_against_local_http_server() {
    let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
    let addr = format!("http://{}", server.server_addr().to_ip().unwrap());
    for _ in 0..100 {
        match std::net::TcpListener::bind(server.server_addr().to_ip().unwrap()) {
            Ok(l) => drop(l),
            Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => break,
            Err(_) => {}
        }
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    let handle = std::thread::spawn(move || {
        for _ in 0..2 {
            let mut req = server.recv().unwrap();
            if req.method() == &tiny_http::Method::Post {
                let mut body = String::new();
                req.as_reader().read_to_string(&mut body).unwrap();
                assert!(body.contains("\"change_id\""));
                assert!(body.contains("\"c1\""));
                req.respond(
                    tiny_http::Response::from_string(
                        r#"{"results":[{"change_id":"c1","status":"APPLIED","server_sequence":42}]}"#,
                    )
                    .with_status_code(200),
                )
                .unwrap();
            } else {
                let url = req.url().to_string();
                assert!(url.starts_with("/api/v1/sync/changes?after=10"));
                req.respond(
                    tiny_http::Response::from_string(
                        r#"{"changes":[{"server_sequence":11,"change_id":"c2","origin_device_id":"dev-b","entity_type":"note","entity_id":"n1","operation":"CREATE","version":1,"payload":{"title":"x"}}],"next_sequence":11,"has_more":false}"#,
                    )
                    .with_status_code(200),
                )
                .unwrap();
            }
        }
    });
    let transport = UreqTransport::new(&addr, "jwt-token").unwrap();
    let push = PushChange {
        change_id: "c1".to_string(),
        origin_device_id: "dev-a".to_string(),
        entity_type: EntityType::Note,
        entity_id: "n1".to_string(),
        operation: Operation::Create,
        base_version: 0,
        version: 1,
        content_hash: None,
        payload: json!({}),
    };
    let push_resp = transport.push_changes(&[push]).unwrap();
    assert_eq!(push_resp.results.len(), 1);
    assert_eq!(push_resp.results[0].change_id, "c1");
    assert_eq!(push_resp.results[0].server_sequence, Some(42));
    let pull_resp = transport.pull_changes(10, 500).unwrap();
    assert_eq!(pull_resp.changes.len(), 1);
    assert_eq!(pull_resp.changes[0].entity_id, "n1");
    assert_eq!(pull_resp.next_sequence, 11);
    handle.join().unwrap();
}

// TASK-011/021: 实体快照 payload 规范化 → content_hash 可复现
#[test]
fn content_hash_is_reproducible() {
    let p = json!({"b": 1, "a": "x", "nested": {"y": [1,2]}});
    let h1 = crate::util::snapshot_hash(&p);
    let h2 = crate::util::snapshot_hash(&p);
    assert_eq!(h1, h2);
    assert!(h1.starts_with("sha256:"));
}

struct MockBlobTransport {
    reserved: Mutex<HashSet<String>>,
    sizes: Mutex<HashMap<String, u64>>,
    chunks: Mutex<HashMap<(String, u32), Vec<u8>>>,
    completed: Mutex<HashMap<String, Vec<u8>>>,
    corrupt: Mutex<HashMap<String, Vec<u8>>>,
    fail_download: Mutex<HashSet<String>>,
    fail_chunk0_once: Mutex<HashSet<String>>,
    init_calls: AtomicUsize,
    put_calls: AtomicUsize,
    complete_calls: AtomicUsize,
    get_calls: AtomicUsize,
    get_order: Mutex<Vec<String>>,
}

impl MockBlobTransport {
    fn new() -> Self {
        MockBlobTransport {
            reserved: Mutex::new(HashSet::new()),
            sizes: Mutex::new(HashMap::new()),
            chunks: Mutex::new(HashMap::new()),
            completed: Mutex::new(HashMap::new()),
            corrupt: Mutex::new(HashMap::new()),
            fail_download: Mutex::new(HashSet::new()),
            fail_chunk0_once: Mutex::new(HashSet::new()),
            init_calls: AtomicUsize::new(0),
            put_calls: AtomicUsize::new(0),
            complete_calls: AtomicUsize::new(0),
            get_calls: AtomicUsize::new(0),
            get_order: Mutex::new(Vec::new()),
        }
    }

    fn init_calls(&self) -> usize {
        self.init_calls.load(Ordering::SeqCst)
    }

    fn put_calls(&self) -> usize {
        self.put_calls.load(Ordering::SeqCst)
    }

    fn complete_calls(&self) -> usize {
        self.complete_calls.load(Ordering::SeqCst)
    }

    fn get_calls(&self) -> usize {
        self.get_calls.load(Ordering::SeqCst)
    }
}

impl BlobTransport for MockBlobTransport {
    fn init_upload(&self, blob_id: &str, size: u64, _mime_type: Option<&str>) -> Result<InitResult> {
        self.init_calls.fetch_add(1, Ordering::SeqCst);
        if self.completed.lock().unwrap().contains_key(blob_id) {
            return Ok(InitResult::Exists);
        }
        self.sizes.lock().unwrap().insert(blob_id.to_string(), size);
        self.reserved.lock().unwrap().insert(blob_id.to_string());
        Ok(InitResult::Created)
    }

    fn put_chunk(&self, blob_id: &str, index: u32, data: &[u8]) -> Result<()> {
        self.put_calls.fetch_add(1, Ordering::SeqCst);
        if index == 0 && self.fail_chunk0_once.lock().unwrap().remove(blob_id) {
            return Err(Error::Sync("simulated chunk failure".into()));
        }
        if !self.reserved.lock().unwrap().contains(blob_id) {
            return Err(Error::Sync("no upload session".into()));
        }
        self.chunks
            .lock()
            .unwrap()
            .insert((blob_id.to_string(), index), data.to_vec());
        Ok(())
    }

    fn complete_upload(&self, blob_id: &str) -> Result<()> {
        self.complete_calls.fetch_add(1, Ordering::SeqCst);
        let size = *self.sizes.lock().unwrap().get(blob_id).unwrap_or(&0);
        let mut indices: Vec<u32> = self
            .chunks
            .lock()
            .unwrap()
            .keys()
            .filter(|(id, _)| id == blob_id)
            .map(|(_, i)| *i)
            .collect();
        indices.sort_unstable();
        let mut data = Vec::new();
        for i in indices {
            data.extend_from_slice(&self.chunks.lock().unwrap()[&(blob_id.to_string(), i)]);
        }
        if data.len() as u64 != size {
            return Err(Error::Sync("size mismatch".into()));
        }
        if crate::util::sha256_hex(&data) != blob_id.trim_start_matches("sha256:") {
            return Err(Error::Sync("hash mismatch".into()));
        }
        self.completed.lock().unwrap().insert(blob_id.to_string(), data);
        Ok(())
    }

    fn download(&self, blob_id: &str) -> Result<Vec<u8>> {
        self.get_calls.fetch_add(1, Ordering::SeqCst);
        self.get_order.lock().unwrap().push(blob_id.to_string());
        if self.fail_download.lock().unwrap().contains(blob_id) {
            return Err(Error::Sync("simulated download failure".into()));
        }
        if let Some(c) = self.corrupt.lock().unwrap().get(blob_id) {
            return Ok(c.clone());
        }
        self.completed
            .lock()
            .unwrap()
            .get(blob_id)
            .cloned()
            .ok_or_else(|| Error::Sync("blob not uploaded".into()))
    }
}

fn blob_dir(tag: &str) -> std::path::PathBuf {
    keep_tempdir(tag).join("blobs")
}

// TASK-075: 上传往返 + 本地去重跳过（内容寻址幂等）
#[test]
fn blob_upload_roundtrip_and_local_dedup() {
    let manager = BlobManager::new(blob_dir("blob-rt"));
    let content = "hello blob".repeat(10_000);
    let blob_id = crate::util::blob_id_of(content.as_bytes());
    manager.write_local_atomic(&blob_id, content.as_bytes()).unwrap();
    assert!(manager.has_local(&blob_id));

    let transport = MockBlobTransport::new();
    assert!(manager.upload(&transport, &blob_id, Some("text/markdown")).unwrap());
    assert!(transport.completed.lock().unwrap().contains_key(&blob_id));
    assert_eq!(transport.init_calls(), 1);
    assert_eq!(transport.put_calls(), 1);
    assert_eq!(transport.complete_calls(), 1);

    assert!(!manager.upload(&transport, &blob_id, Some("text/markdown")).unwrap());
    assert_eq!(transport.init_calls(), 2);
    assert_eq!(transport.put_calls(), 1);
    assert_eq!(transport.complete_calls(), 1);

    let missing = crate::util::blob_id_of(b"never local");
    assert!(!manager.upload(&transport, &missing, None).unwrap());
    assert_eq!(transport.init_calls(), 2);

    let fresh = BlobManager::new(blob_dir("blob-rt2"));
    assert!(!fresh.has_local(&blob_id));
    fresh.download(&transport, &blob_id).unwrap();
    assert_eq!(fresh.read_local(&blob_id).unwrap(), content.as_bytes());
    assert_eq!(transport.get_calls(), 1);
    fresh.download(&transport, &blob_id).unwrap();
    assert_eq!(transport.get_calls(), 1);
}

// TASK-075: 上传失败重试（退避模式复用，测试用固定短延时）
#[test]
fn blob_upload_retries_transient_chunk_failure() {
    let manager = BlobManager::with_retry_delay(blob_dir("blob-retry"), 1);
    let content = vec![b'x'; 4 * 1024 * 1024 + 10];
    let blob_id = crate::util::blob_id_of(&content);
    manager.write_local_atomic(&blob_id, &content).unwrap();
    let transport = MockBlobTransport::new();
    transport.fail_chunk0_once.lock().unwrap().insert(blob_id.clone());
    assert!(manager.upload(&transport, &blob_id, None).unwrap());
    assert_eq!(transport.put_calls(), 3);
    assert_eq!(transport.complete_calls(), 1);
}

// TASK-076: 下载 SHA-256 校验失败 → 重试排期 → 连续 5 次 FAILED
#[test]
fn download_queue_corrupt_retries_then_failed() {
    let conn = memory_conn();
    let queue = DownloadQueue::new(blob_dir("dlq-corrupt"));
    let blob_id = crate::util::blob_id_of(b"good bytes");
    let transport = MockBlobTransport::new();
    transport.completed.lock().unwrap().insert(blob_id.clone(), b"good bytes".to_vec());
    transport.corrupt.lock().unwrap().insert(blob_id.clone(), b"evil bytes".to_vec());
    let now = now_ms();
    crate::blob::download_queue::enqueue(&conn, &blob_id, 0, now).unwrap();
    for attempt in 1..=5 {
        let report = queue.run(&conn, &transport, now).unwrap();
        assert_eq!(report.failed, 1);
        assert_eq!(report.downloaded, 0);
        let (state, retry_count): (String, i64) = conn
            .query_row(
                "SELECT state, retry_count FROM blob_download_queue WHERE blob_id = ?1",
                rusqlite::params![blob_id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(retry_count, attempt);
        if attempt < 5 {
            assert_eq!(state, "PENDING");
        } else {
            assert_eq!(state, "FAILED");
        }
        conn
            .execute("UPDATE blob_download_queue SET next_retry_at = 0", [])
            .unwrap();
    }
    assert!(!queue.manager().has_local(&blob_id));
    let report = queue.run(&conn, &transport, now).unwrap();
    assert_eq!(report.failed, 0);
    assert_eq!(report.downloaded, 0);
    assert_eq!(crate::blob::download_queue::failed_count(&conn).unwrap(), 1);
}

// TASK-076: 下载成功 → 原子落盘（文件完整、无残留临时文件）+ 队列清理
#[test]
fn download_queue_priority_order_and_atomic_write() {
    let conn = memory_conn();
    let dir = blob_dir("dlq-prio");
    let queue = DownloadQueue::new(&dir);
    let low = crate::util::blob_id_of(b"low priority content");
    let high = crate::util::blob_id_of(b"high priority content");
    let transport = MockBlobTransport::new();
    transport.completed.lock().unwrap().insert(low.clone(), b"low priority content".to_vec());
    transport.completed.lock().unwrap().insert(high.clone(), b"high priority content".to_vec());
    let now = now_ms();
    crate::blob::download_queue::enqueue(&conn, &low, 0, now).unwrap();
    crate::blob::download_queue::enqueue(&conn, &high, 5, now).unwrap();
    let report = queue.run(&conn, &transport, now).unwrap();
    assert_eq!(report.downloaded, 2);
    assert_eq!(*transport.get_order.lock().unwrap(), vec![high.clone(), low.clone()]);
    assert_eq!(queue.manager().read_local(&high).unwrap(), b"high priority content");
    assert_eq!(queue.manager().read_local(&low).unwrap(), b"low priority content");
    let leftovers: Vec<_> = std::fs::read_dir(&dir)
        .unwrap()
        .filter_map(|e| e.ok())
        .filter(|e| e.file_name().to_string_lossy().starts_with(".tmp-"))
        .collect();
    assert!(leftovers.is_empty());
    assert_eq!(crate::blob::download_queue::queue_count(&conn).unwrap(), 0);
    let again = queue.run(&conn, &transport, now).unwrap();
    assert_eq!(again.downloaded, 0);
    assert_eq!(again.skipped, 0);
}

// TASK-076: enqueue_pending 扫描 blobs 表：本地已有跳过，被活跃笔记引用者高优先级
#[test]
fn download_queue_enqueue_pending_scans_missing_blobs() {
    let conn = memory_conn();
    let queue = DownloadQueue::new(blob_dir("dlq-pending"));
    let now = now_ms();
    let local_blob = crate::util::blob_id_of(b"already local");
    let remote_blob = crate::util::blob_id_of(b"remote only");
    let other_blob = crate::util::blob_id_of(b"other remote");
    queue.manager().write_local_atomic(&local_blob, b"already local").unwrap();
    for (id, size) in [
        (&local_blob, 13i64),
        (&remote_blob, 11),
        (&other_blob, 13),
    ] {
        let b = crate::models::Blob {
            blob_id: id.clone(),
            size,
            mime_type: Some("text/markdown".into()),
            storage_type: "file".into(),
            storage_path: "".into(),
            created_at: now,
        };
        repo::upsert_blob(&conn, &b).unwrap();
    }
    let mut note = Note::new("n-ref".into(), "t".into(), "text".into(), now);
    note.blob_id = Some(remote_blob.clone());
    repo::insert_note(&conn, &note).unwrap();
    let enqueued = queue.enqueue_pending(&conn, now).unwrap();
    assert_eq!(enqueued, 2);
    let picked = crate::blob::download_queue::pickup(&conn, now, 10).unwrap();
    assert_eq!(picked, vec![remote_blob.clone(), other_blob.clone()]);
    assert_eq!(crate::blob::download_queue::pending_count(&conn).unwrap(), 2);
}

// TASK-073/074: UreqBlobTransport 与本地 mock HTTP server 往返
#[test]
fn ureq_blob_transport_against_local_http_server() {
    let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
    let addr = format!("http://{}", server.server_addr().to_ip().unwrap());
    for _ in 0..100 {
        match std::net::TcpListener::bind(server.server_addr().to_ip().unwrap()) {
            Ok(l) => drop(l),
            Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => break,
            Err(_) => {}
        }
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    let content: Vec<u8> = b"roundtrip over http".to_vec();
    let blob_id = crate::util::blob_id_of(&content);
    let server_content = content.clone();
    let handle = std::thread::spawn(move || {
        for _ in 0..4 {
            let mut req = server.recv().unwrap();
            let url = req.url().to_string();
            match req.method() {
                &tiny_http::Method::Post if url.ends_with("/blobs/init") => {
                    let mut body = String::new();
                    req.as_reader().read_to_string(&mut body).unwrap();
                    assert!(body.contains("\"blob_id\""));
                    req.respond(
                        tiny_http::Response::from_string(
                            r#"{"status":"CREATED","upload_session_id":"s1"}"#,
                        )
                        .with_status_code(200),
                    )
                    .unwrap();
                }
                &tiny_http::Method::Put => {
                    let mut body = Vec::new();
                    req.as_reader().read_to_end(&mut body).unwrap();
                    assert_eq!(body, server_content);
                    req.respond(
                        tiny_http::Response::from_string(r#"{"status":"ok"}"#).with_status_code(200),
                    )
                    .unwrap();
                }
                &tiny_http::Method::Post if url.ends_with("/complete") => {
                    req.respond(
                        tiny_http::Response::from_string(r#"{"status":"ok","size":21}"#)
                            .with_status_code(200),
                    )
                    .unwrap();
                }
                &tiny_http::Method::Get => {
                    req.respond(
                        tiny_http::Response::from_data(server_content.clone()).with_status_code(200),
                    )
                    .unwrap();
                }
                other => panic!("unexpected method {other}"),
            }
        }
    });
    let transport = UreqBlobTransport::new(&addr, "jwt-token").unwrap();
    let init = transport
        .init_upload(&blob_id, content.len() as u64, Some("text/markdown"))
        .unwrap();
    assert_eq!(init, InitResult::Created);
    transport.put_chunk(&blob_id, 0, &content).unwrap();
    transport.complete_upload(&blob_id).unwrap();
    let got = transport.download(&blob_id).unwrap();
    assert_eq!(got, content);
    handle.join().unwrap();
}

// ─── 回归测试（评审修复） ────────────────────────────────────────────

/// P0-3：pull payload 中的恶意 blob_id（路径穿越）必须被拒绝
#[test]
fn apply_rejects_traversal_blob_id() {
    let conn = memory_conn();
    let mut pc = note_pull_change(1, &uuid_v7(), "note-evil", 1, "evil", "CREATE");
    pc.payload["blob_id"] = json!("../../etc/passwd");
    let err = apply::apply(&conn, &pc);
    assert!(err.is_err(), "note payload with traversal blob_id must be rejected");

    let mut pc2 = note_pull_change(2, &uuid_v7(), "note-evil2", 1, "evil2", "CREATE");
    pc2.entity_type = "blob".to_string();
    pc2.entity_id = "sha256:..%2f..%2fetc%2fpasswd".to_string();
    pc2.payload = json!({"size": 10});
    assert!(apply::apply(&conn, &pc2).is_err(), "blob entity with invalid id must be rejected");
}

/// P0-3：blobs_get / blobs_exists 拒绝非法 blob_id
#[test]
fn commands_reject_invalid_blob_id_lookup() {
    let core = core();
    assert!(matches!(
        core.blobs_get("../../etc/passwd"),
        Err(Error::BlobMissing(_))
    ));
    assert!(!core.blobs_exists("not-a-blob-id").unwrap());
}

/// P0-4：pull 的 blob 下载完成后回填 storage_path，FTS 内容搜索生效
#[test]
fn pull_blob_download_backfills_fts_content_search() {
    let conn = memory_conn();
    let content = "独一无二的可搜索正文内容 unique-searchable-body";
    let blob_id = crate::util::blob_id_of(content.as_bytes());

    // 模拟 pull：note 变更（带 blob_id）+ blob 变更
    let mut pc = note_pull_change(1, &uuid_v7(), "note-pulled", 1, "拉取的笔记", "CREATE");
    pc.payload["blob_id"] = json!(blob_id);
    apply::apply(&conn, &pc).unwrap();
    let pb = PullChange {
        server_sequence: 2,
        change_id: uuid_v7(),
        origin_device_id: Some("device-b".to_string()),
        entity_type: "blob".to_string(),
        entity_id: blob_id.clone(),
        operation: "CREATE".to_string(),
        version: 1,
        payload: json!({"size": content.len(), "mime_type": "text/markdown"}),
    };
    apply::apply(&conn, &pb).unwrap();

    // 下载前：内容搜索搜不到（文件不在本地）
    assert!(crate::fts::search(&conn, "searchable", 10).unwrap().is_empty());

    // 下载完成 → 回填 storage_path + FTS 重建
    let dir = blob_dir("fts-backfill");
    let queue = DownloadQueue::new(&dir);
    queue.manager().write_local_atomic(&blob_id, content.as_bytes()).unwrap();
    crate::blob::download_queue::backfill_after_download(&conn, queue.manager(), &blob_id).unwrap();

    let results = crate::fts::search(&conn, "searchable", 10).unwrap();
    assert_eq!(results.len(), 1, "content search must find pulled note after download");
    assert_eq!(results[0].note_id, "note-pulled");
}

/// P1-1：trash_empty 后不得遗留幽灵分支（子笔记指向已清除的父）
#[test]
fn trash_empty_removes_ghost_branches() {
    let mut core = core();
    let parent = core.create_note("root", "父笔记", "text").unwrap();
    let child = core.create_note(&parent.note_id, "子笔记", "text").unwrap();

    // 仅删除父笔记并清空回收站
    core.delete_note(&parent.note_id).unwrap();
    core.trash_empty().unwrap();

    let ghost_branches: i64 = core
        .db()
        .connection()
        .query_row(
            "SELECT COUNT(*) FROM branches WHERE parent_note_id = ?1 OR child_note_id = ?1",
            rusqlite::params![parent.note_id],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(ghost_branches, 0, "no branches may reference the purged note");

    // 子笔记未被删除：其分支随父清除后应出现在根列表（而非彻底不可见）
    let root = core.list_notes(None, false).unwrap();
    assert!(root.iter().any(|m| m.note_id == child.note_id), "orphaned child must surface at root");
}

/// P1-6：schema 版本高于当前构建时必须报错，而非静默跳过迁移
#[test]
fn migration_rejects_newer_schema_version() {
    let mut conn = Connection::open_in_memory().unwrap();
    conn.execute_batch(
        "CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL);
         INSERT INTO schema_migrations (version, applied_at) VALUES (99, 0);",
    )
    .unwrap();
    assert!(migration::migrate(&mut conn).is_err(), "newer schema must be rejected");
}

/// P1-2：blob 的 DELETE pull 变更应删除行，而非用空 payload 覆盖元数据
#[test]
fn apply_blob_delete_removes_row() {
    let conn = memory_conn();
    let blob_id = crate::util::blob_id_of(b"to be deleted");
    let pb = PullChange {
        server_sequence: 1,
        change_id: uuid_v7(),
        origin_device_id: Some("device-b".to_string()),
        entity_type: "blob".to_string(),
        entity_id: blob_id.clone(),
        operation: "CREATE".to_string(),
        version: 1,
        payload: json!({"size": 15, "mime_type": "text/markdown"}),
    };
    apply::apply(&conn, &pb).unwrap();
    assert!(repo::get_blob(&conn, &blob_id).unwrap().is_some());

    let pd = PullChange {
        server_sequence: 2,
        change_id: uuid_v7(),
        origin_device_id: Some("device-b".to_string()),
        entity_type: "blob".to_string(),
        entity_id: blob_id.clone(),
        operation: "DELETE".to_string(),
        version: 2,
        payload: json!({}),
    };
    apply::apply(&conn, &pd).unwrap();
    assert!(repo::get_blob(&conn, &blob_id).unwrap().is_none(), "DELETE change must remove blob row");
}

// ─── v1.1.1 回归：级联删除/恢复、根级 branch、冲突空覆盖防护 ───────────────

#[test]
fn folder_delete_cascades_to_descendants() {
    let mut core = core();
    let folder = core.create_note("root", "dir", "folder").unwrap();
    let child = core.create_note(&folder.note_id, "child", "text").unwrap();
    let grand = core.create_note(&child.note_id, "grand", "text").unwrap();

    core.delete_note(&folder.note_id).unwrap();

    // 三个节点全部进回收站
    let trash = core.trash_list().unwrap();
    let ids: Vec<&str> = trash.iter().map(|n| n.note_id.as_str()).collect();
    assert!(ids.contains(&folder.note_id.as_str()));
    assert!(ids.contains(&child.note_id.as_str()));
    assert!(ids.contains(&grand.note_id.as_str()));

    // 恢复孙子 → 祖先链（目录+子目录）一并复活，不产生幽灵
    core.restore_note(&grand.note_id).unwrap();
    let notes = core.list_notes(Some(&folder.note_id), false).unwrap();
    assert_eq!(notes.len(), 1);
    assert_eq!(notes[0].note_id, child.note_id);
    let inner = core.list_notes(Some(&child.note_id), false).unwrap();
    assert_eq!(inner.len(), 1);
    assert_eq!(inner[0].note_id, grand.note_id);
}

#[test]
fn root_note_has_branch_and_survives_move_to_root() {
    let mut core = core();
    // v1.1.1 起根级创建即带 parent='root' branch
    let note = core.create_note("root", "at root", "text").unwrap();
    let root_list = core.list_notes(None, false).unwrap();
    assert_eq!(root_list.len(), 1);

    let folder = core.create_note("root", "f", "folder").unwrap();
    core.move_note_to(&note.note_id, &folder.note_id, None).unwrap();
    let in_folder = core.list_notes(Some(&folder.note_id), false).unwrap();
    assert_eq!(in_folder.len(), 1);

    // 移回 root：不再消失（branch 移回 parent='root'，根列表可见；folder 仍在 root）
    core.move_note_to(&note.note_id, "root", None).unwrap();
    let back_at_root = core.list_notes(None, false).unwrap();
    assert_eq!(back_at_root.len(), 2);
    let ids: Vec<&str> = back_at_root.iter().map(|n| n.note_id.as_str()).collect();
    assert!(ids.contains(&note.note_id.as_str()), "moved-back note must be visible at root");
    assert!(ids.contains(&folder.note_id.as_str()));
}

#[test]
fn conflict_resolve_refuses_when_conflict_blob_missing() {
    let mut core = core();
    let orig = core.create_note("root", "orig", "text").unwrap();
    core.save_content(&orig.note_id, "v1").unwrap();
    // 手工造一个冲突副本（正常路径由 apply 在冲突时生成；这里直接构造数据）
    let conflict = core.create_note("root", "conflict copy", "text").unwrap();
    core.save_content(&conflict.note_id, "conflict content").unwrap();
    core.db_mut().connection().execute(
        "UPDATE notes SET conflict_of_note_id = ?1 WHERE note_id = ?2",
        rusqlite::params![orig.note_id, conflict.note_id],
    ).unwrap();

    // 模拟 Lazy Download 未完成：删除本地 blob 文件但保留 note.blob_id
    {
        let blob_id: String = core.db().connection()
            .query_row("SELECT blob_id FROM notes WHERE note_id = ?1", rusqlite::params![conflict.note_id], |r| r.get(0))
            .unwrap();
        let path = core.blobs().local_path(&blob_id);
        std::fs::remove_file(&path).unwrap();
    }

    let err = core.conflicts_resolve(&conflict.note_id, true).unwrap_err();
    assert!(matches!(err, crate::Error::InvalidArgument(_)), "must refuse instead of empty overwrite: {err}");
    // 原笔记内容未被破坏
    let (_, content) = core.get_content(&orig.note_id).unwrap();
    assert_eq!(content.unwrap(), "v1");
}

// ─── v1.1.2 回归：跨设备级联 payload、环防护、空 parent、blob 失败浮出 ──────

/// 跨设备级联删除：A 删目录，B 应用其 Change 后，子孙必须保留各自的标题/类型，
/// 而不是被目录的字段覆盖（v1.1.1 曾用目录快照 + 换 note_id 的错误 payload）。
#[test]
fn cascade_delete_payload_carries_each_descendants_own_snapshot() {
    let mut a = core();
    let folder = a.create_note("root", "目录标题", "folder").unwrap();
    let child_text = a.create_note(&folder.note_id, "子笔记标题", "text").unwrap();
    a.save_content(&child_text.note_id, "子笔记正文").unwrap();
    let child_folder = a.create_note(&folder.note_id, "子目录标题", "folder").unwrap();
    let grandchild = a.create_note(&child_folder.note_id, "孙笔记标题", "text").unwrap();

    a.delete_note(&folder.note_id).unwrap();

    // 取 A 的全部待推送 Change（含级联 DELETE），逐条应用到 B
    let mut b = core();
    let ids = outbox::dequeue_batch(a.db().connection(), now_ms(), 100).unwrap();
    assert!(ids.len() >= 5, "folder + 3 children + blob/note changes, got {}", ids.len());
    for (seq, change_id) in ids.iter().enumerate() {
        let ch = change::get_change(a.db().connection(), change_id).unwrap().unwrap();
        let pc = PullChange {
            server_sequence: seq as i64 + 1,
            change_id: ch.change_id.clone(),
            origin_device_id: Some("device-a".to_string()),
            entity_type: format!("{:?}", ch.entity_type).to_lowercase(),
            entity_id: ch.entity_id.clone(),
            operation: format!("{:?}", ch.operation).to_uppercase(),
            version: ch.version,
            payload: ch.payload.clone(),
        };
        apply::apply(b.db_mut().connection(), &pc).unwrap();
    }

    // B 端：全部进回收站，且字段是各节点自己的
    for (id, title, note_type) in [
        (&folder.note_id, "目录标题", "folder"),
        (&child_text.note_id, "子笔记标题", "text"),
        (&child_folder.note_id, "子目录标题", "folder"),
        (&grandchild.note_id, "孙笔记标题", "text"),
    ] {
        let note = b.get_note(id).unwrap();
        assert!(note.is_deleted, "{title} must be deleted on B");
        assert_eq!(note.title, title, "title must survive cascade on B (own snapshot)");
        assert_eq!(note.note_type, note_type, "type must survive cascade on B (own snapshot)");
    }

    // 恢复孙笔记：祖先链复活 + 内容未被目录字段污染
    b.restore_note(&grandchild.note_id).unwrap();
    let revived = b.get_note(&grandchild.note_id).unwrap();
    assert!(!revived.is_deleted);
    assert_eq!(revived.title, "孙笔记标题");
}

/// 环防护：目录不能移进自己的子孙
#[test]
fn tree_move_rejects_cycle() {
    let mut core = core();
    let outer = core.create_note("root", "outer", "folder").unwrap();
    let inner = core.create_note(&outer.note_id, "inner", "folder").unwrap();
    let note = core.create_note("root", "n", "text").unwrap();

    // outer → inner（自己的子孙）：拒绝
    let err = core.move_note_to(&outer.note_id, &inner.note_id, None).unwrap_err();
    assert!(matches!(err, Error::InvalidArgument(_)), "cycle must be rejected: {err}");
    // 自身 → 自身：拒绝
    let err = core.move_note_to(&note.note_id, &note.note_id, None);
    assert!(err.is_err());
    // 普通移动不受影响
    core.move_note_to(&note.note_id, &inner.note_id, None).unwrap();
}

/// 空 parent 归一化为 root：create_note("") 不得产生不可见笔记
#[test]
fn create_note_empty_parent_lands_at_root() {
    let mut core = core();
    let meta = core.create_note("", "visible?", "text").unwrap();
    let root_list = core.list_notes(None, false).unwrap();
    assert!(root_list.iter().any(|n| n.note_id == meta.note_id), "note with empty parent must be visible at root");
}

/// blob 失败浮出到同步报告：下载失败不得被吞掉变成"已同步"
#[test]
fn sync_report_surfaces_blob_failures() {
    use crate::engine::SyncEngine;
    use crate::sync::{PullResponse, PushResponse};

    // 只成功 push、pull 恒空的传输层
    struct OkSyncTransport;
    impl SyncTransport for OkSyncTransport {
        fn push_changes(&self, changes: &[PushChange]) -> Result<PushResponse> {
            Ok(PushResponse {
                results: changes
                    .iter()
                    .map(|c| PushResult {
                        change_id: c.change_id.clone(),
                        status: "OK".into(),
                        server_sequence: Some(1),
                    })
                    .collect(),
            })
        }
        fn pull_changes(&self, _after: i64, _limit: u32) -> Result<PullResponse> {
            Ok(PullResponse { changes: vec![], has_more: false, next_sequence: 1 })
        }
    }

    let mut core = core();
    let transport = MockBlobTransport::new();
    // 造一个"服务端存在但下载必失败"的缺失 blob：本地 note 引用它但本地无文件
    {
        let mut note = Note::new(uuid_v7(), "missing blob".into(), "text".into(), now_ms());
        let blob_id = "sha256:".to_string() + &crate::util::sha256_hex(b"remote-only");
        note.blob_id = Some(blob_id.clone());
        // 模拟 pull 落库形态：blobs 行存在但 storage_path 为空（文件未下载）
        let blob_row = crate::models::Blob {
            blob_id: blob_id.clone(),
            size: 12,
            mime_type: Some("text/markdown".into()),
            storage_type: "file".into(),
            storage_path: String::new(),
            created_at: now_ms(),
        };
        let tx = core.db_mut().tx().unwrap();
        repo::insert_note(&tx, &note).unwrap();
        repo::insert_blob(&tx, &blob_row).unwrap();
        tx.commit().unwrap();
        transport.fail_download.lock().unwrap().insert(blob_id);
    }

    let engine = SyncEngine::new(Box::new(OkSyncTransport), "client-x");
    let report = core.sync_trigger_with_blob(&engine, &transport).unwrap();
    assert_eq!(report.blob_download_failed, 1, "download failure must surface in report, got {report:?}");
}

// GC：编辑历史产生的无引用 blob 被回收；当前引用与未推送 blob 必须保留
#[test]
fn blob_gc_removes_unreferenced_and_keeps_live() {
    let mut core = core();
    let note = core.create_note("root", "n", "text").unwrap();
    let old = core.save_content(&note.note_id, "v1").unwrap();
    let cur = core.save_content(&note.note_id, "v2").unwrap();
    assert!(core.blobs().has_local(&old));
    // 两份 blob 的 CREATE change 均未 push（outbox 有行）——不得回收
    assert_eq!(core.blob_gc().unwrap(), 0);
    assert!(core.blobs().has_local(&old));
    // 模拟已 push（服务端有副本）：清空 outbox 后旧版本可回收
    core.db().connection().execute("DELETE FROM sync_outbox", []).unwrap();
    assert_eq!(core.blob_gc().unwrap(), 1);
    assert!(!core.blobs().has_local(&old), "unreferenced blob file removed");
    assert!(core.blobs().has_local(&cur), "live blob kept");
    // 二次 GC 幂等
    assert_eq!(core.blob_gc().unwrap(), 0);
}

// GC：tombstone 引用的 blob（恢复需要）不回收；trash_empty 物理删除后随挂钩回收
#[test]
fn blob_gc_keeps_tombstone_referenced_blob_until_trash_empty() {
    let mut core = core();
    let note = core.create_note("root", "n", "text").unwrap();
    let blob = core.save_content(&note.note_id, "content").unwrap();
    core.db().connection().execute("DELETE FROM sync_outbox", []).unwrap();
    core.delete_note(&note.note_id).unwrap();
    assert_eq!(core.blob_gc().unwrap(), 0, "tombstone still references blob");
    assert!(core.blobs().has_local(&blob));
    core.trash_empty().unwrap();
    assert!(!core.blobs().has_local(&blob), "trash_empty triggers blob GC");
}
