use lightnote_core::engine::SyncEngine;
use lightnote_hardening::{create_note, force_sending_stale, outbox_rows, recover_stale, setup};
use lightnote_slice::{Client, Server};

fn pair() -> (Server, Client, Client) {
    let (server, mut clients) = setup(&["A", "B"]);
    let a = clients.remove(0);
    let b = clients.remove(0);
    (server, a, b)
}

#[test]
fn delete_vs_update_conflict_preserves_content() {
    let (server, mut a, mut b) = pair();

    let note_id = a.core.create_note("root", "共享笔记", "text").expect("create").note_id;
    a.sync();
    b.sync();

    a.core.delete_note(&note_id).expect("delete");
    a.sync();
    assert!(a.core.get_note(&note_id).expect("get").is_deleted);

    b.core.update_note(&note_id, "B 的修改").expect("update");
    b.sync();

    a.sync();

    let main = a.core.get_note(&note_id).expect("get main");
    assert!(main.is_deleted, "主版本应保持已删除（先提交者）");
    let conflict = a
        .core
        .conflicts_list()
        .expect("conflicts")
        .into_iter()
        .find(|c| c.conflict_of_note_id == note_id)
        .expect("conflict copy exists");
    assert_eq!(conflict.title, "B 的修改（冲突副本）", "冲突副本应保留 B 的标题");
    assert_eq!(b.core.conflicts_list().expect("conflicts").len(), 1, "B 也应看到冲突副本");
    drop(server);
}

#[test]
fn version_guard_skips_older_snapshot_but_advances_cursor() {
    let (server, mut a, mut b) = pair();

    let note_id = a.core.create_note("root", "标题 v1", "text").expect("create").note_id;
    a.sync();
    b.sync();

    a.core.update_note(&note_id, "标题 v2").expect("update");
    a.sync();

    b.core.update_note(&note_id, "B v2").expect("update");
    b.core.update_note(&note_id, "B v3").expect("update");
    let cursor_before = b.cursor();
    b.sync();

    let after = b.core.get_note(&note_id).expect("get");
    assert_eq!(after.title, "B v3", "本地 v3 不允许被远端 v2 快照回退");
    assert!(b.cursor() > cursor_before, "cursor 必须推进（跳过应用不等于跳过拉取）");
    assert_eq!(after.version, 3);

    b.sync();
    let v = b.core.get_note(&note_id).expect("get");
    assert_eq!(v.version, 3, "重复同步不产生重复数据");
    drop(server);
}

#[test]
fn cursor_persists_across_restart_and_replay_is_idempotent() {
    let (server, mut a, b) = pair();

    for i in 0..3 {
        create_note(&mut a, &format!("笔记 {i}"), "x");
    }
    a.sync();

    let db_path = b.dir.path().join("data.db");
    let blobs_path = b.dir.path().join("blobs");
    let token = server.login("B");
    let mut b_slow = b;
    b_slow.limit_pull(1);
    b_slow.sync();
    assert_eq!(b_slow.cursor(), 7, "批量同步后 cursor=7（3 笔记 + 1 blob，内容寻址去重）；去重是预期行为");

    let reopened = Client::from_paths(&server, "B", &db_path, &blobs_path, &token);
    assert_eq!(reopened.cursor(), 7, "重启后 cursor 持久化");
    let mut reopened = reopened;
    reopened.sync();
    assert_eq!(
        reopened.core.list_notes(None, false).expect("list").len(),
        3,
        "重放幂等，无重复"
    );
    assert_eq!(reopened.cursor(), 7, "无新变更，cursor 不前进");
    drop(server);
}

#[test]
fn outbox_recovery_sending_to_pending_then_push() {
    let (server, mut clients) = setup(&["A"]);
    let mut a = clients.remove(0);

    create_note(&mut a, "离线创建", "内容");
    assert!(a.outbox_count() >= 1, "本地修改产生 outbox");

    force_sending_stale(&a);
    assert!(recover_stale(&a) >= 1, "崩溃遗留的 SENDING 恢复为 PENDING");

    a.sync();
    assert_eq!(a.outbox_count(), 0, "恢复后成功推送");
    drop(server);
}

#[test]
fn retry_backoff_keeps_outbox_and_recovers_after_server_up() {
    let mut server = Server::start();
    let mut a = Client::new(&server, "A");

    server.kill();
    create_note(&mut a, "断网时创建", "内容");
    let failed = a.sync_allow_error();
    assert!(failed, "server 已停，同步应失败");

    let rows = outbox_rows(&a);
    assert!(!rows.is_empty(), "应有待重试的 outbox");
    let (_, state, retry, next) = rows[0].clone();
    assert_eq!(state, "PENDING");
    assert!(retry >= 1, "retry_count 应递增，实际 {retry}");
    assert!(next > lightnote_core::util::now_ms(), "next_retry_at 应在未来（退避）");

    server.restart();
    lightnote_hardening::reset_retry_timers(&a);
    a.sync();
    assert_eq!(a.outbox_count(), 0, "server 恢复后推送成功");
    drop(server);
}

#[test]
fn server_crash_recovery_keeps_entity_change_sequence_consistent() {
    let (mut server, mut a, mut b) = pair();

    let n1 = create_note(&mut a, "崩溃前", "1");
    a.sync();

    server.kill();
    let n2 = create_note(&mut a, "崩溃期间离线创建", "2");
    let n3 = create_note(&mut a, "崩溃期间离线创建 2", "3");
    a.sync_allow_error();

    server.restart();
    lightnote_hardening::reset_retry_timers(&a);
    a.sync();
    b.sync();

    assert_eq!(b.core.get_note(&n1).expect("n1").title, "崩溃前");
    assert_eq!(b.core.get_note(&n2).expect("n2").title, "崩溃期间离线创建");
    assert_eq!(b.core.get_note(&n3).expect("n3").title, "崩溃期间离线创建 2");

    let mut a2 = Client::new(&server, "A2");
    a2.sync();
    let all = a2.core.list_notes(None, false).expect("list");
    assert_eq!(all.len(), 3, "新设备全量拉取一致");
    drop(server);
}

#[test]
fn duplicate_pull_change_id_is_idempotent() {
    let (server, mut a, mut b) = pair();

    let note_id = create_note(&mut a, "幂等", "x");
    a.sync();

    let transport = b.raw_transport();
    let engine = SyncEngine::new(Box::new(transport), format!("client-{}", b.name));
    let pulled = engine.pull_once(b.core.db_mut()).expect("first pull");
    assert_eq!(pulled, 3, "一次拉取 3 条变更（note CREATE + blob CREATE + note UPDATE）");
    let pulled_again = engine.pull_once(b.core.db_mut()).expect("second pull");
    assert_eq!(pulled_again, 0, "重复 Pull 不产生重复应用");
    assert_eq!(b.core.get_note(&note_id).expect("get").title, "幂等");
    assert_eq!(b.core.list_notes(None, false).expect("list").len(), 1);
    drop(server);
}
