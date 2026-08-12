//! Phase 7 §70 异常场景矩阵剩余项的自动化固化（架构 v1.1 §70）。
//!
//! 覆盖原有 7 个 hardening 用例未触及的行：
//! - Server 已提交但响应丢失 / 同一 Change 重复 Push → ALREADY_APPLIED
//! - Pull 中断 → Cursor 不推进
//! - Pull 应用失败 → Transaction Rollback + Cursor 不推进
//! - 长期离线 → 增量 Pull 分页聚合
//! - Blob 上传中断 → Chunk Resume
//! - Blob Hash 错误 → Reject
//! - 服务端序列出现 gap → 客户端容忍（§51）
//! - 设备永久离线 → Tombstone 不 GC

use lightnote_core::blob::manager::CHUNK_SIZE;
use lightnote_core::blob::{BlobTransport, InitResult, UreqBlobTransport};
use lightnote_core::engine::SyncEngine;
use lightnote_core::error::Error;
use lightnote_core::sync::{PullChange, PullResponse, PushChange, PushResponse, SyncTransport};
use lightnote_core::util::blob_id_of;
use lightnote_core::Result;
use lightnote_hardening::{create_note, reset_retry_timers, setup};
use lightnote_slice::{Client, Server};
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

fn pair() -> (Server, Client, Client) {
    let (server, mut clients) = setup(&["A", "B"]);
    let a = clients.remove(0);
    let b = clients.remove(0);
    (server, a, b)
}

/// 用真实 transport 重建 engine（撤销失败注入）
fn real_engine(client: &Client) -> SyncEngine {
    SyncEngine::new(
        Box::new(client.raw_transport()),
        format!("client-{}", client.name),
    )
}

fn client_id(client: &Client) -> String {
    format!("client-{}", client.name)
}

// ───────────────────────── 注入式 Transport ─────────────────────────

/// 转发 push 到真实服务端（服务端提交），但前 N 次向 engine 返回 Err，
/// 模拟“Server 已提交但响应丢失”。
struct ResponseLossTransport {
    inner: Box<dyn SyncTransport>,
    fail_remaining: AtomicUsize,
}

impl ResponseLossTransport {
    fn new(inner: Box<dyn SyncTransport>, fail_calls: usize) -> Self {
        Self {
            inner,
            fail_remaining: AtomicUsize::new(fail_calls),
        }
    }
}

impl SyncTransport for ResponseLossTransport {
    fn push_changes(&self, changes: &[PushChange]) -> Result<PushResponse> {
        let resp = self.inner.push_changes(changes)?;
        let mut remaining = self.fail_remaining.load(Ordering::SeqCst);
        if remaining > 0 {
            remaining -= 1;
            self.fail_remaining.store(remaining, Ordering::SeqCst);
            return Err(Error::Sync(
                "simulated connection reset after server commit".into(),
            ));
        }
        Ok(resp)
    }
    fn pull_changes(&self, after: i64, limit: u32) -> Result<PullResponse> {
        self.inner.pull_changes(after, limit)
    }
}

/// 前 N 次 pull_changes 直接返回 Err（模拟 Pull 中途断网）。
struct PullFailTransport {
    inner: Box<dyn SyncTransport>,
    fail_remaining: AtomicUsize,
}

impl PullFailTransport {
    fn new(inner: Box<dyn SyncTransport>, fail_calls: usize) -> Self {
        Self {
            inner,
            fail_remaining: AtomicUsize::new(fail_calls),
        }
    }
}

impl SyncTransport for PullFailTransport {
    fn push_changes(&self, changes: &[PushChange]) -> Result<PushResponse> {
        self.inner.push_changes(changes)
    }
    fn pull_changes(&self, after: i64, limit: u32) -> Result<PullResponse> {
        let mut remaining = self.fail_remaining.load(Ordering::SeqCst);
        if remaining > 0 {
            remaining -= 1;
            self.fail_remaining.store(remaining, Ordering::SeqCst);
            return Err(Error::Sync("simulated pull connection drop".into()));
        }
        self.inner.pull_changes(after, limit)
    }
}

/// 首次 pull：在真实响应末尾追加一条无法解析的 change（entity_type 非法），
/// 触发 apply 内部 parse 失败 → 整批事务回滚 + cursor 不推进。
struct PoisonPullTransport {
    inner: Box<dyn SyncTransport>,
    fired: AtomicBool,
}

impl PoisonPullTransport {
    fn new(inner: Box<dyn SyncTransport>) -> Self {
        Self {
            inner,
            fired: AtomicBool::new(false),
        }
    }
}

impl SyncTransport for PoisonPullTransport {
    fn push_changes(&self, changes: &[PushChange]) -> Result<PushResponse> {
        self.inner.push_changes(changes)
    }
    fn pull_changes(&self, after: i64, limit: u32) -> Result<PullResponse> {
        let mut resp = self.inner.pull_changes(after, limit)?;
        if !self.fired.swap(true, Ordering::SeqCst) && !resp.changes.is_empty() {
            resp.changes.push(PullChange {
                server_sequence: resp.next_sequence + 1000,
                change_id: "__poison_apply_failure__".into(),
                origin_device_id: Some("server".into()),
                entity_type: "__not_a_real_entity__".into(),
                entity_id: "__poison__".into(),
                operation: "CREATE".into(),
                version: 1,
                payload: Value::Null,
            });
        }
        Ok(resp)
    }
}

/// 不触达服务端的 mock：返回带 gap 的 server_sequence（如 10, 20, 30），
/// 用于断言客户端 cursor = 实际最后序列，而非 after+1（§51 gap 容忍）。
struct GapMockTransport {
    fired: AtomicBool,
}

fn mk_note_change(seq: i64, entity_id: &str) -> PullChange {
    PullChange {
        server_sequence: seq,
        change_id: format!("change-{entity_id}"),
        origin_device_id: Some("remote-device".into()),
        entity_type: "note".into(),
        entity_id: entity_id.into(),
        operation: "CREATE".into(),
        version: 1,
        payload: json!({
            "title": format!("gap note {entity_id}"),
            "note_type": "text",
        }),
    }
}

impl SyncTransport for GapMockTransport {
    fn push_changes(&self, _changes: &[PushChange]) -> Result<PushResponse> {
        Ok(PushResponse { results: vec![] })
    }
    fn pull_changes(&self, after: i64, _limit: u32) -> Result<PullResponse> {
        if after == 0 && !self.fired.swap(true, Ordering::SeqCst) {
            Ok(PullResponse {
                changes: vec![
                    mk_note_change(10, "gap-10"),
                    mk_note_change(20, "gap-20"),
                    mk_note_change(30, "gap-30"),
                ],
                next_sequence: 30,
                has_more: false,
            })
        } else {
            Ok(PullResponse {
                changes: vec![],
                next_sequence: after,
                has_more: false,
            })
        }
    }
}

// ───────────────────────────── 用例 ─────────────────────────────

/// §70: 「Server 已提交但响应丢失 | 重试，依赖 changeId 幂等」+「同一 Change 重复 Push | ALREADY_APPLIED」
#[test]
fn push_response_lost_then_retry_returns_already_applied() {
    let (server, mut clients) = setup(&["A"]);
    let mut a = clients.remove(0);

    let note_id = create_note(&mut a, "响应丢失", "内容");

    // 首次推送：真实服务端已提交，但向 engine 返回 Err（模拟响应丢失）
    a.engine = SyncEngine::new(
        Box::new(ResponseLossTransport::new(Box::new(a.raw_transport()), 1)),
        client_id(&a),
    );
    let failed = a.sync_allow_error();
    assert!(failed, "响应丢失：首次同步应失败");
    assert!(a.outbox_count() >= 1, "outbox 必须保留，等待重试");

    // 关键证明：服务端在响应丢失前已提交 —— 全新设备 C 立即可拉到该 note
    let mut c = Client::new(&server, "C");
    c.sync();
    assert_eq!(
        c.core.list_notes(None, false).expect("list").len(),
        1,
        "服务端已提交（响应丢失 ≠ 未提交）"
    );

    // 重置退避计时器，切回真实 transport 重试 → ALREADY_APPLIED → outbox 清空
    reset_retry_timers(&a);
    a.engine = real_engine(&a);
    a.sync();
    assert_eq!(a.outbox_count(), 0, "重试命中 ALREADY_APPLIED，outbox 清空");

    // 再次全新设备拉取：依旧 1 条（重试未产生重复）
    let mut d = Client::new(&server, "D");
    d.sync();
    assert_eq!(d.core.list_notes(None, false).expect("list").len(), 1);
    assert_eq!(d.core.get_note(&note_id).expect("get").title, "响应丢失");
    drop(server);
}

/// §70: 「Pull 中断 | Cursor 不推进」
#[test]
fn pull_network_failure_keeps_cursor_then_recovers() {
    let (server, mut a, mut b) = pair();
    create_note(&mut a, "远端笔记 1", "x");
    create_note(&mut a, "远端笔记 2", "y");
    a.sync();

    let cursor_before = b.cursor(); // 从未同步过
    assert_eq!(cursor_before, 0);

    // Pull 阶段断网：前 2 次 pull_changes 返回 Err
    b.engine = SyncEngine::new(
        Box::new(PullFailTransport::new(Box::new(b.raw_transport()), 2)),
        client_id(&b),
    );
    let failed = b.sync_allow_error();
    assert!(failed, "Pull 断网：同步应失败");
    assert_eq!(b.cursor(), 0, "Pull 失败时 cursor 绝不推进");
    assert_eq!(
        b.core.list_notes(None, false).expect("list").len(),
        0,
        "未拉到任何数据"
    );

    // 恢复网络：重试 → 正常拉取
    b.engine = real_engine(&b);
    b.sync();
    assert!(b.cursor() > 0, "恢复后 cursor 推进");
    assert_eq!(b.core.list_notes(None, false).expect("list").len(), 2);
    drop(server);
}

/// §70: 「Pull 应用失败 | Transaction Rollback」+ Cursor 不推进
#[test]
fn pull_apply_failure_rolls_back_batch_and_keeps_cursor() {
    let (server, mut a, mut b) = pair();
    let real_id = create_note(&mut a, "应被回滚的批次", "x");
    a.sync();

    assert_eq!(b.cursor(), 0);

    // 首次 pull：真实变更后追加一条非法 entity_type 的 change → apply 内部失败
    b.engine = SyncEngine::new(
        Box::new(PoisonPullTransport::new(Box::new(b.raw_transport()))),
        client_id(&b),
    );
    let failed = b.sync_allow_error();
    assert!(failed, "apply 失败：同步应失败");

    assert_eq!(b.cursor(), 0, "整批事务回滚：cursor 不推进");
    assert!(
        b.core.get_note(&real_id).is_err(),
        "已应用的变更必须被回滚（事务原子性）"
    );
    assert_eq!(
        b.core.list_notes(None, false).expect("list").len(),
        0,
        "无半应用状态"
    );

    // 恢复：真实 pull 从 cursor=0 重放 → 干净应用
    b.engine = real_engine(&b);
    b.sync();
    assert!(b.cursor() > 0);
    assert_eq!(b.core.get_note(&real_id).expect("get").title, "应被回滚的批次");
    drop(server);
}

/// §70: 「长期离线 | 增量 Pull」+ §51 has_more 分页聚合
#[test]
fn long_offline_incremental_pull_paginates_and_converges() {
    let (server, mut clients) = setup(&["A", "B"]);
    let mut a = clients.remove(0);
    let mut b = clients.remove(0);

    // B 长期离线，A 产出大量变更
    const N: usize = 25;
    let mut ids = Vec::with_capacity(N);
    for i in 0..N {
        ids.push(create_note(
            &mut a,
            &format!("离线笔记 {i:02}"),
            &format!("内容 {i}"),
        ));
    }
    a.sync();

    // B 上线，强制每页 2 条，触发 has_more 翻页聚合
    b.limit_pull(2);
    b.sync();

    for (i, id) in ids.iter().enumerate() {
        assert_eq!(
            b.core.get_note(id).expect("get").title,
            format!("离线笔记 {i:02}"),
            "笔记 {i} 应被聚合拉取"
        );
    }
    assert_eq!(
        b.core.list_notes(None, false).expect("list").len(),
        N,
        "全部聚合，无丢失"
    );

    // 再次同步幂等（无重复）
    let cursor_after = b.cursor();
    b.sync();
    assert_eq!(b.cursor(), cursor_after, "无新变更 cursor 不前进");
    assert_eq!(b.core.list_notes(None, false).expect("list").len(), N);
    drop(server);
}

/// §70: 「Blob 上传中断 | Chunk Resume」
#[test]
fn blob_chunk_upload_resume_after_interruption() {
    let (server, mut clients) = setup(&["A"]);
    let a = clients.remove(0);

    // >4MB 内容 → 2 个分片
    let content: Vec<u8> = (0..(5 * 1024 * 1024)).map(|i| (i % 251) as u8).collect();
    let blob_id = blob_id_of(&content);

    let bt = UreqBlobTransport::new(&server.base_url, a.token()).expect("blob transport");

    // init + 仅上传第 0 分片，模拟第 1 分片上传前中断
    let init = bt
        .init_upload(&blob_id, content.len() as u64, Some("application/octet-stream"))
        .expect("init");
    assert_eq!(init, InitResult::Created);
    bt.put_chunk(&blob_id, 0, &content[0..CHUNK_SIZE])
        .expect("chunk 0");
    // 中断：不继续 chunk 1，也不 complete

    // 恢复：重新 init（复用同一 session），chunk 0 重复上传被安全忽略，chunk 1 上传，complete
    let init2 = bt
        .init_upload(&blob_id, content.len() as u64, Some("application/octet-stream"))
        .expect("init2");
    assert_eq!(init2, InitResult::Created, "session 复用（非 EXISTS）");
    bt.put_chunk(&blob_id, 0, &content[0..CHUNK_SIZE])
        .expect("chunk 0 retry (idempotent)");
    bt.put_chunk(&blob_id, 1, &content[CHUNK_SIZE..])
        .expect("chunk 1");
    bt.complete_upload(&blob_id).expect("complete");

    // 下载校验：内容与 hash 一致
    let got = bt.download(&blob_id).expect("download");
    assert_eq!(got, content, "续传完成后内容与原始一致");
    drop(server);
}

/// §70: 「Blob Hash 错误 | Reject」
#[test]
fn blob_hash_mismatch_is_rejected() {
    let (server, mut clients) = setup(&["A"]);
    let a = clients.remove(0);

    let real_content = vec![b'x'; 4096];
    let real_id = blob_id_of(&real_content); // blob_id = sha256(real_content)
    let wrong_content = vec![b'y'; 4096]; // 与 blob_id 不匹配

    let bt = UreqBlobTransport::new(&server.base_url, a.token()).expect("blob transport");
    bt.init_upload(&real_id, real_content.len() as u64, None)
        .expect("init");
    bt.put_chunk(&real_id, 0, &wrong_content)
        .expect("上传错误内容（分片层不校验 hash）");

    // complete 重算 SHA-256 与 blob_id 比对 → 必须被 Reject
    let complete = bt.complete_upload(&real_id);
    assert!(complete.is_err(), "SHA-256 不一致应被 Reject");

    // 被 reject 的 blob 不可下载
    let dl = bt.download(&real_id);
    assert!(dl.is_err(), "被 reject 的 blob 不应被保留");
    drop(server);
}

/// §70: 「服务端序列出现 gap | 正常现象，客户端不得假设连续（§51）」
#[test]
fn pull_tolerates_server_sequence_gaps() {
    let (server, mut clients) = setup(&["B"]);
    let mut b = clients.remove(0);

    // 用 mock 返回带 gap 的序列 10, 20, 30（11~19、21~29 为 gap）
    b.engine = SyncEngine::new(
        Box::new(GapMockTransport {
            fired: AtomicBool::new(false),
        }),
        client_id(&b),
    );
    b.sync();

    // cursor = 实际最后序列 30，而非 after+1（=1）
    assert_eq!(b.cursor(), 30, "cursor 记录最后一条已应用序列，不假设连续");
    assert!(b.core.get_note("gap-10").is_ok());
    assert!(b.core.get_note("gap-20").is_ok());
    assert!(b.core.get_note("gap-30").is_ok());

    // 再次同步：mock 返回空，cursor 不变，无重复
    let cursor = b.cursor();
    b.sync();
    assert_eq!(b.cursor(), cursor);
    drop(server);
}

/// §70: 「设备永久离线 | 暂不 GC Tombstone」
/// —— 当前版本无 GC 机制，tombstone 必须在多次同步后仍可查（include_deleted=true）。
#[test]
fn tombstone_not_gced_across_syncs() {
    let (server, mut a, mut b) = pair();
    let id = create_note(&mut a, "将被删除", "x");
    a.sync();
    b.sync();
    assert_eq!(b.core.list_notes(None, false).expect("list").len(), 1);

    // A 删除 → tombstone
    a.core.delete_note(&id).expect("delete");
    a.sync();

    // B 同步：active 列表消失，但 tombstone 仍可查
    b.sync();
    assert_eq!(b.core.list_notes(None, false).expect("list").len(), 0);
    let tomb = b.core.get_note(&id).expect("tombstone 仍在");
    assert!(tomb.is_deleted, "tombstone 标记 is_deleted=true");
    assert_eq!(
        b.core.list_notes(None, true).expect("list incl deleted").len(),
        1,
        "include_deleted=true 仍可见（未被 GC）"
    );

    // 多轮同步后 tombstone 依旧在（无 GC）
    for _ in 0..3 {
        a.sync();
        b.sync();
    }
    assert!(b.core.get_note(&id).expect("tombstone 持久存在").is_deleted);
    assert_eq!(b.core.list_notes(None, true).expect("list").len(), 1);
    drop(server);
}
