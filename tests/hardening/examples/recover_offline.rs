//! 长时间离线恢复 —— 运维级验证脚本（Phase 7）
//!
//! 模拟一台设备长时间离线后的完整恢复路径：
//!   1. 双方初始同步到一致
//!   2. B 离线：远端 A 持续产出新笔记（含附件），B 完全不同步
//!   3. B 离线期间本地继续编辑，堆积 sync_outbox
//!   4. B 重连 → 强制完整 reconciliation
//!      （sync_once 内部先 recover_stale_sending，再 push 全部 outbox，再增量 pull）
//!   5. 校验最终一致性：outbox 清空 / 远端笔记全到达 / 本地编辑全上推 /
//!      blob 补拉齐全 / 双方笔记集合一致
//!
//! 运行（需 Go 工具链以编译 lightnote-server）：
//!     cargo run --example recover_offline
//!
//! 说明：本脚本不是新增能力——engine 在每次 sync_once 都会自动做崩溃恢复 +
//! 退避重试 + 增量 pull + blob 补拉。此脚本把这些步骤一次性走完并断言结果，
//! 供运维 / QA 在部署环境按需执行。

use lightnote_core::blob::UreqBlobTransport;
use lightnote_hardening::{create_note, reset_retry_timers, setup};
use lightnote_slice::{Client, Server};
use std::collections::HashSet;

fn note_set(c: &Client) -> HashSet<(String, String)> {
    c.core
        .list_notes(None, false)
        .expect("list_notes")
        .into_iter()
        .map(|n| (n.note_id, n.title))
        .collect()
}

fn phase(msg: &str) {
    println!("\n== {msg} ==");
}

/// Push + Pull + Blob 上传/下载（确保附件字节随变更一起复制）
fn sync_blob(server: &Server, c: &mut Client) {
    let bt = UreqBlobTransport::new(&server.base_url, c.token()).expect("blob transport");
    c.core
        .sync_trigger_with_blob(&c.engine, &bt)
        .expect("sync with blob");
}

fn main() {
    let (server, mut clients) = setup(&["A", "B"]);
    let mut a = clients.remove(0);
    let mut b = clients.remove(0);

    phase("Phase 1: 双方初始同步");
    sync_blob(&server, &mut a);
    sync_blob(&server, &mut b);
    println!("A notes={}, cursor={} | B notes={}, cursor={}",
        note_set(&a).len(), a.cursor(), note_set(&b).len(), b.cursor());

    phase("Phase 2: B 离线 —— A 持续产出新笔记（含附件）");
    const REMOTE: usize = 10;
    let mut remote_ids = Vec::with_capacity(REMOTE);
    for i in 0..REMOTE {
        // 内容 > 一行，确保经 blob 存储（内容寻址）
        let content = format!("远端正文 {i} ").repeat(30);
        remote_ids.push((i, create_note(&mut a, &format!("远端笔记 {i:02}"), &content)));
    }
    // A 必须用 blob-sync：否则只推送 blob 元数据，字节不上传，B 后续无法补拉
    sync_blob(&server, &mut a);
    println!("A 产出 {REMOTE} 篇笔记并推送（含附件字节）；B 不同步（cursor={} 仍停在初始）", b.cursor());

    phase("Phase 3: B 离线期间本地编辑（堆积 sync_outbox）");
    const LOCAL: usize = 5;
    let mut local_ids = Vec::with_capacity(LOCAL);
    for i in 0..LOCAL {
        local_ids.push((i, create_note(&mut b, &format!("本地离线 {i:02}"), "离线编辑内容")));
    }
    println!("B 本地堆积 outbox={} 条（等待重连推送）", b.outbox_count());

    phase("Phase 4: B 重连 —— 强制完整恢复");
    // recover_stale_sending 由 sync_once 内部自动执行；
    // reset_retry_timers 让离线期间失败的重试立即就绪。
    reset_retry_timers(&b);
    let bt = UreqBlobTransport::new(&server.base_url, b.token()).expect("blob transport");
    let report = b
        .core
        .sync_trigger_with_blob(&b.engine, &bt)
        .expect("recovery sync");
    println!("恢复 sync 完成：pushed={}, pulled={}, blob_queued={}, cursor={}",
        report.pushed, report.pulled, report.blob_queued, b.cursor());
    phase("Phase 5: 一致性校验");

    // 5.1 outbox 必须清空
    assert_eq!(b.outbox_count(), 0, "恢复后 outbox 必须清空");
    println!("[OK] B outbox 已清空");

    // 5.2 远端笔记全部到达 B
    for (i, id) in &remote_ids {
        let n = b.core.get_note(id).expect("远端笔记应到达 B");
        assert_eq!(n.title, format!("远端笔记 {i:02}"));
    }
    println!("[OK] {REMOTE} 篇远端笔记全部补拉到达 B");

    // 5.3 远端附件 blob 已补拉、内容一致
    for (i, id) in &remote_ids {
        let (_blob_id, content) = b.core.get_content(id).expect("get_content");
        let content = content.expect("blob 必须已补拉到本地");
        assert!(
            content.contains(&format!("远端正文 {i}")),
            "远端笔记 {i} 的 blob 内容必须一致"
        );
    }
    println!("[OK] {REMOTE} 个附件 blob 已补拉且内容一致");

    // 5.4 B 的本地离线编辑已上推到服务端：A 拉取后应能见到
    a.sync();
    for (i, id) in &local_ids {
        let n = a.core.get_note(id).expect("B 的本地离线笔记应已上推并到达 A");
        assert_eq!(n.title, format!("本地离线 {i:02}"));
    }
    println!("[OK] {LOCAL} 篇本地离线编辑已上推并被 A 见到");

    // 5.5 双方笔记集合完全一致（无冲突副本，因为编辑的是不同笔记）
    let sa = note_set(&a);
    let sb = note_set(&b);
    assert_eq!(sa, sb, "恢复后双方笔记集合必须一致");
    assert_eq!(sa.len(), REMOTE + LOCAL, "总笔记数 = 远端 + 本地");
    println!("[OK] 双方笔记集合一致，共 {} 篇", sa.len());

    // 5.6 幂等：再次恢复不产生任何变化
    let before = note_set(&b);
    let cursor_before = b.cursor();
    b.core.sync_trigger_with_blob(&b.engine, &bt).expect("idempotent re-sync");
    assert_eq!(note_set(&b), before, "重复恢复不得改变数据");
    assert_eq!(b.cursor(), cursor_before, "无新变更时 cursor 不前进");
    println!("[OK] 重复恢复幂等（cursor={} 不变）", cursor_before);

    println!("\n== 恢复校验全部通过 ✅ ==");
    println!("  远端笔记补拉: {REMOTE}");
    println!("  本地离线推送: {LOCAL}");
    println!("  附件 blob 补拉: {REMOTE}");
    println!("  B 最终 cursor: {}", b.cursor());
    drop(server);
}
