use lightnote_core::sync::SyncTransport;
use lightnote_slice::{Client, Server};

#[test]
fn t1_a_create_propagates_to_b() {
    let server = Server::start();
    let mut a = Client::new(&server, "a");
    let mut b = Client::new(&server, "b");

    let note = a.core.create_note("root", "Docker 网络排障笔记", "text").unwrap();
    let blob_id = a.core.save_content(&note.note_id, "# 正文\n\n网络排查记录").unwrap();
    a.sync();
    b.sync();

    let got = b.note(&note.note_id);
    assert_eq!(got.title, "Docker 网络排障笔记");
    assert_eq!(got.blob_id.as_deref(), Some(blob_id.as_str()));
    assert!(!got.is_deleted);
    assert_eq!(got.version, 2);
    assert!(lightnote_core::repo::blob_exists(b.core.db().connection(), &blob_id).unwrap());
    assert_eq!(a.cursor(), b.cursor());
    assert_eq!(b.outbox_count(), 0);

    b.seed_blob_from(&a, &blob_id);
    let (bid, content) = b.core.get_content(&note.note_id).unwrap();
    assert_eq!(bid.as_deref(), Some(blob_id.as_str()));
    assert_eq!(content.as_deref(), Some("# 正文\n\n网络排查记录"));
}

#[test]
fn t2_a_update_propagates_to_b() {
    let server = Server::start();
    let mut a = Client::new(&server, "a");
    let mut b = Client::new(&server, "b");

    let note = a.core.create_note("root", "v1", "text").unwrap();
    a.sync();
    b.sync();
    assert_eq!(b.note(&note.note_id).version, 1);

    a.core.update_note(&note.note_id, "v2-标题").unwrap();
    a.core.save_content(&note.note_id, "# v2 正文").unwrap();
    a.sync();
    b.sync();

    let got = b.note(&note.note_id);
    assert_eq!(got.title, "v2-标题");
    assert_eq!(got.version, 3);
    let blob_id = got.blob_id.clone().expect("blob_id");
    b.seed_blob_from(&a, &blob_id);
    let (_, content) = b.core.get_content(&note.note_id).unwrap();
    assert_eq!(content.as_deref(), Some("# v2 正文"));
}

#[test]
fn t3_a_delete_tombstone_propagates_to_b() {
    let server = Server::start();
    let mut a = Client::new(&server, "a");
    let mut b = Client::new(&server, "b");

    let note = a.core.create_note("root", "to-delete", "text").unwrap();
    a.sync();
    b.sync();
    assert!(!b.note(&note.note_id).is_deleted);

    a.core.delete_note(&note.note_id).unwrap();
    a.sync();
    b.sync();

    let got = b.note(&note.note_id);
    assert!(got.is_deleted);
    assert_eq!(got.version, 2);
    assert!(b.core.list_notes(None, false).unwrap().is_empty());
    assert_eq!(b.core.trash_list().unwrap().len(), 1);
}

#[test]
fn t4_b_update_propagates_back_to_a() {
    let server = Server::start();
    let mut a = Client::new(&server, "a");
    let mut b = Client::new(&server, "b");

    let note = b.core.create_note("root", "b-origin", "text").unwrap();
    b.sync();
    a.sync();
    assert_eq!(a.note(&note.note_id).title, "b-origin");

    b.core.update_note(&note.note_id, "b-edited").unwrap();
    b.sync();
    a.sync();

    let got = a.note(&note.note_id);
    assert_eq!(got.title, "b-edited");
    assert_eq!(got.version, 2);
    assert_eq!(b.outbox_count(), 0);
}

#[test]
fn t5_push_idempotent() {
    let server = Server::start();
    let mut a = Client::new(&server, "a");
    let mut b = Client::new(&server, "b");

    let note = a.core.create_note("root", "idem", "text").unwrap();
    let ids = lightnote_core::outbox::dequeue_batch(
        a.core.db().connection(),
        lightnote_core::util::now_ms(),
        10,
    )
    .unwrap();
    assert_eq!(ids.len(), 1);
    let ch = lightnote_core::change::get_change(a.core.db().connection(), &ids[0])
        .unwrap()
        .unwrap();
    assert_eq!(ch.entity_id, note.note_id);
    let push = lightnote_core::sync::PushChange {
        change_id: ch.change_id,
        origin_device_id: ch.origin_device_id,
        entity_type: ch.entity_type,
        entity_id: ch.entity_id,
        operation: ch.operation,
        base_version: ch.base_version,
        version: ch.version,
        content_hash: ch.content_hash,
        payload: ch.payload,
    };

    let transport = a.raw_transport();
    let r1 = &transport.push_changes(&[push.clone()]).unwrap().results[0];
    assert_eq!(r1.status, "APPLIED");
    let seq1 = r1.server_sequence.expect("server_sequence");
    let r2 = &transport.push_changes(&[push]).unwrap().results[0];
    assert_eq!(r2.status, "ALREADY_APPLIED");
    assert_eq!(r2.server_sequence, Some(seq1));

    b.sync();
    let notes = b.core.list_notes(None, false).unwrap();
    assert_eq!(notes.len(), 1);
    let got = b.note(&note.note_id);
    assert_eq!(got.title, "idem");
    assert_eq!(got.version, 1);
    assert_eq!(b.cursor(), seq1);
}

#[test]
fn t6_cursor_batch_pull() {
    let server = Server::start();
    let mut a = Client::new(&server, "a");
    let mut b = Client::new(&server, "b");

    let mut ids = Vec::new();
    for i in 0..3 {
        let n = a.core.create_note("root", &format!("batch-{i}"), "text").unwrap();
        ids.push(n.note_id);
    }
    a.sync();
    assert_eq!(a.cursor(), 3);
    assert_eq!(a.outbox_count(), 0);

    b.limit_pull(1);
    let mut last = 0i64;
    let mut pulled = 0usize;
    loop {
        let n = b.engine.pull_once(b.core.db_mut()).unwrap();
        if n == 0 {
            break;
        }
        let c = b.cursor();
        assert!(c > last, "cursor must advance monotonically: {last} -> {c}");
        last = c;
        pulled += n;
    }
    assert_eq!(pulled, 3);
    assert_eq!(last, 3);
    assert_eq!(b.cursor(), a.cursor());
    let notes = b.core.list_notes(None, false).unwrap();
    assert_eq!(notes.len(), 3);
    for id in &ids {
        assert_eq!(b.note(id).version, 1);
    }
    assert_eq!(b.outbox_count(), 0);
}

#[test]
fn t7_version_guard_conflict_preserved() {
    let server = Server::start();
    let mut a = Client::new(&server, "a");
    let mut b = Client::new(&server, "b");

    let note = a.core.create_note("root", "shared note", "text").unwrap();
    a.sync();
    b.sync();
    assert_eq!(b.note(&note.note_id).version, 1);

    a.core.update_note(&note.note_id, "A 先提交").unwrap();
    a.sync();

    b.core.update_note(&note.note_id, "B 后提交").unwrap();
    b.sync();
    assert_eq!(b.outbox_count(), 0);

    a.sync();
    b.sync();

    let a_main = a.note(&note.note_id);
    let b_main = b.note(&note.note_id);
    assert_eq!(a_main.title, "A 先提交");
    assert_eq!(b_main.title, "A 先提交");
    assert_eq!(a_main.conflict_of_note_id, None);
    assert_eq!(b_main.version, 2);

    let ca = a.core.conflicts_list().unwrap();
    let cb = b.core.conflicts_list().unwrap();
    assert_eq!(ca.len(), 1);
    assert_eq!(cb.len(), 1);
    assert_eq!(ca[0].conflict_of_note_id, note.note_id);
    assert_eq!(ca[0].note_id, cb[0].note_id);
    assert_eq!(ca[0].title, "B 后提交（冲突副本）");
    assert_eq!(cb[0].title, "B 后提交（冲突副本）");
    assert_eq!(b.core.get_note(&cb[0].note_id).unwrap().version, 1);
}

#[test]
fn t8_jwt_required() {
    let server = Server::start();
    let agent = ureq::Agent::new();

    let resp = agent
        .post(&format!("{}/api/v1/sync/push", server.base_url))
        .send_json(serde_json::json!({"changes": []}));
    match resp {
        Ok(r) => panic!("push without token succeeded: {}", r.status()),
        Err(ureq::Error::Status(401, _)) => {}
        Err(e) => panic!("push without token: unexpected error {e}"),
    }

    let resp = agent
        .get(&format!("{}/api/v1/sync/changes?after=0&limit=10", server.base_url))
        .call();
    match resp {
        Ok(r) => panic!("pull without token succeeded: {}", r.status()),
        Err(ureq::Error::Status(401, _)) => {}
        Err(e) => panic!("pull without token: unexpected error {e}"),
    }

    let resp = agent
        .post(&format!("{}/api/v1/sync/push", server.base_url))
        .set("Authorization", "Bearer not-a-jwt")
        .send_json(serde_json::json!({"changes": []}));
    match resp {
        Ok(r) => panic!("push with invalid token succeeded: {}", r.status()),
        Err(ureq::Error::Status(401, _)) => {}
        Err(e) => panic!("push with invalid token: unexpected error {e}"),
    }
}

#[test]
fn t9_pull_does_not_rewrite_outbox() {
    let server = Server::start();
    let mut a = Client::new(&server, "a");

    a.core.create_note("root", "loop-guard", "text").unwrap();
    a.sync();
    assert_eq!(a.outbox_count(), 0);
    assert_eq!(a.core.sync_status().unwrap().pending_count, 0);
    let cursor = a.cursor();
    assert!(cursor >= 1);

    for _ in 0..3 {
        a.sync();
        assert_eq!(a.outbox_count(), 0, "pull must never re-enqueue into outbox");
        assert_eq!(a.core.sync_status().unwrap().pending_count, 0);
    }
    assert!(a.cursor() >= cursor);
    let dupes = a
        .core
        .list_notes(None, true)
        .unwrap()
        .iter()
        .filter(|n| n.title == "loop-guard")
        .count();
    assert_eq!(dupes, 1);
}
