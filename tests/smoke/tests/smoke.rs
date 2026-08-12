use lightnote_core::blob::UreqBlobTransport;
use lightnote_slice::{Client, Server};

fn pair() -> (Server, Client, Client) {
    let (mut server, mut clients) = lightnote_slice::setup(&["A", "B"]);
    let a = clients.remove(0);
    let b = clients.remove(0);
    (server, a, b)
}

fn sync_with_blob(server: &Server, c: &mut Client) {
    let bt = UreqBlobTransport::new(&server.base_url, c.token()).expect("blob transport");
    c.core
        .sync_trigger_with_blob(&c.engine, &bt)
        .expect("sync with blob");
}

#[test]
fn smoke_1_create_note() {
    let (mut server, mut a, _b) = pair();
    let id = a
        .core
        .create_note("root", "我的第一篇笔记", "text")
        .expect("create")
        .note_id;
    let n = a.core.get_note(&id).expect("get");
    assert_eq!(n.title, "我的第一篇笔记");
    drop(server);
}

#[test]
fn smoke_2_modify_note() {
    let (mut server, mut a, _b) = pair();
    let id = a.core.create_note("root", "初始", "text").expect("create").note_id;
    a.core.update_note(&id, "修改后").expect("update");
    a.core.save_content(&id, "# Markdown 正文").expect("save");
    let n = a.core.get_note(&id).expect("get");
    assert_eq!(n.title, "修改后");
    assert_eq!(a.core.get_content(&id).expect("content").1.as_deref(), Some("# Markdown 正文"));
    drop(server);
}

#[test]
fn smoke_3_close_and_reopen_persists() {
    let (mut server, mut a, _b) = pair();
    let id = a.core.create_note("root", "持久化笔记", "text").expect("create").note_id;
    a.core.save_content(&id, "关闭前保存的内容").expect("save");

    let Client { dir, .. } = a;
    let db_path = dir.path().join("data.db");
    let blobs_path = dir.path().join("blobs");
    let token = server.login("A");

    let mut reopened = Client::from_paths(&server, "A", &db_path, &blobs_path, &token);
    let n = reopened.core.get_note(&id).expect("note still exists");
    assert_eq!(n.title, "持久化笔记");
    assert_eq!(
        reopened.core.get_content(&id).expect("content").1.as_deref(),
        Some("关闭前保存的内容"),
        "重新打开后内容仍在"
    );
    drop(server);
}

#[test]
fn smoke_4_second_client_sees_note() {
    let (mut server, mut a, mut b) = pair();
    let id = a.core.create_note("root", "同步给 B", "text").expect("create").note_id;
    a.core.save_content(&id, "B 应该能看到").expect("save");
    sync_with_blob(&server, &mut a);

    sync_with_blob(&server, &mut b);
    assert_eq!(b.core.get_note(&id).expect("get").title, "同步给 B");
    assert_eq!(b.core.get_content(&id).expect("content").1.as_deref(), Some("B 应该能看到"));
    drop(server);
}

#[test]
fn smoke_5_b_edit_then_a_syncs() {
    let (mut server, mut a, mut b) = pair();
    let id = a.core.create_note("root", "双向同步", "text").expect("create").note_id;
    a.core.save_content(&id, "v1").expect("save");
    sync_with_blob(&server, &mut a);
    sync_with_blob(&server, &mut b);

    b.core.update_note(&id, "B 编辑后的标题").expect("update");
    b.core.save_content(&id, "B 编辑的内容").expect("save");
    sync_with_blob(&server, &mut b);

    sync_with_blob(&server, &mut a);
    assert_eq!(a.core.get_note(&id).expect("get").title, "B 编辑后的标题");
    assert_eq!(a.core.get_content(&id).expect("content").1.as_deref(), Some("B 编辑的内容"));
    drop(server);
}

#[test]
fn smoke_6_delete_and_conflict_behavior() {
    let (mut server, mut a, mut b) = pair();
    let id = a.core.create_note("root", "删除测试", "text").expect("create").note_id;
    a.sync();
    b.sync();

    a.core.delete_note(&id).expect("delete");
    a.sync();

    b.core.update_note(&id, "冲突修改").expect("update");
    b.sync();
    a.sync();

    let main = a.core.get_note(&id).expect("get");
    assert!(main.is_deleted, "删除保持");
    let conflicts = a.core.conflicts_list().expect("conflicts");
    assert_eq!(conflicts.len(), 1, "冲突副本已生成且双方可见");
    assert_eq!(b.core.conflicts_list().expect("conflicts").len(), 1);
    drop(server);
}

#[test]
fn smoke_7_attachment_note_opens_on_second_client() {
    let (mut server, mut a, mut b) = pair();

    let id = a.core.create_note("root", "带附件笔记", "text").expect("create").note_id;
    let long_content = "附件正文内容：".repeat(20);
    let blob_id = a
        .core
        .save_content(&id, &long_content)
        .expect("save content");

    let blob_transport = UreqBlobTransport::new(&server.base_url, a.token()).expect("blob transport");
    let uploaded = a.core.blob_upload(&blob_transport, &blob_id, Some("text/markdown")).expect("upload");
    assert!(uploaded, "Blob 应上传成功");
    sync_with_blob(&server, &mut a);

    sync_with_blob(&server, &mut b);
    let b_blob = b.core.get_note(&id).expect("get").blob_id.expect("blob_id");
    assert_eq!(b_blob, blob_id, "B 侧 blob_id 一致");
    assert_eq!(
        b.core.get_content(&id).expect("content").1.as_deref(),
        Some(long_content.as_str()),
        "B 打开附件笔记内容一致（懒下载队列已补拉）"
    );
    drop(server);
}

#[test]
fn smoke_8_search_hits() {
    let (mut server, mut a, _b) = pair();
    let id = a.core.create_note("root", "Rust 所有权笔记", "text").expect("create").note_id;
    a.core.save_content(&id, "所有权是 Rust 的核心概念").expect("save");

    let hits = a.core.search("所有权", 10).expect("search");
    assert!(
        hits.iter().any(|h| h.note_id == id),
        "搜索应命中刚创建的笔记，实际 {hits:?}"
    );
    drop(server);
}

#[test]
fn smoke_10_fts_search_matrix() {
    let (mut server, mut a, _b) = pair();
    let cases = [
        ("SQLite 笔记", "SQLite 是本地数据库"),
        ("Rust 笔记", "Rust 所有权与借用"),
        ("实时备份", "实时备份采用增量同步"),
        ("实时备份架构", "实时备份架构设计文档"),
        ("Rust SQLite 组合", "Rust 结合 SQLite 构建本地优先应用"),
        ("年度计划 2026", "2026 年目标清单"),
        ("短词 a", "单字母 a"),
        ("长句", "这是一个非常长的句子用来测试搜索能否命中完整内容"),
        ("标签测试", "带 #java 标签的笔记"),
    ];
    for (title, content) in &cases {
        let id = a.core.create_note("root", title, "text").expect("create").note_id;
        a.core.save_content(&id, content).expect("save");
    }

    let queries = [
        "SQLite",
        "Rust",
        "实时备份",
        "实时备份架构",
        "Rust SQLite",
        "2026",
        "所有权",
        "单字母",
        "搜索能否命中",
    ];
    for q in queries {
        let hits = a.core.search(q, 50).expect("search");
        assert!(!hits.is_empty(), "查询「{q}」应至少命中一篇，实际 0 篇");
    }

    let exact = a.core.search("实时备份架构", 50).expect("search");
    assert!(
        exact.iter().any(|h| h.title == "实时备份架构"),
        "「实时备份架构」应精确命中对应笔记，实际 {exact:?}"
    );
    drop(server);
}

#[test]
fn smoke_9_multi_branch_and_tree_move() {
    let (mut server, mut a, mut b) = pair();

    let dir_a = a.core.create_note("root", "Linux", "text").expect("create").note_id;
    let dir_b = a.core.create_note("root", "知识库", "text").expect("create").note_id;
    let note = a.core.create_note(&dir_a, "Docker 网络", "text").expect("create").note_id;
    a.sync();
    b.sync();

    b.core.move_note_to(&note, &dir_b, None).expect("move note");
    b.sync();
    a.sync();

    let in_a = a.core.tree_children(&dir_a).expect("children a");
    let in_b = a.core.tree_children(&dir_b).expect("children b");
    assert!(!in_a.iter().any(|n| n.note_id == note), "移动后原目录不应再持有该笔记");
    assert!(in_b.iter().any(|n| n.note_id == note), "新目录持有该笔记");
    assert_eq!(a.core.get_note(&note).expect("get").title, "Docker 网络");
    drop(server);
}
