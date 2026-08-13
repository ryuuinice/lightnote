//! Phase 9.2 — 服务端 + 同步大规模吞吐基准（measure-only，不改生产代码）
//!
//! 核心未知：100K 数据经 Go Server + Sync Engine 首次同步是否可接受。
//! 对 1K / 10K / 100K 分别测量：
//!   - Push（A 把 N 条 outbox 推到 server）
//!   - Pull/Apply/FTS（全新空 B 客户端首同步全部 N 条）
//!   - 正确性断言（outbox 清空 / B 笔记数 == N / 无重复 / cursor 推进 / FTS 可搜）
//!   - 资源（client RSS / server RSS / A·B SQLite 大小）
//!
//! 用法（release 必须）：
//!     cargo run --release --bin throughput 1000
//!     cargo run --release --bin throughput 10000
//!     cargo run --release --bin throughput 100000

use lightnote_core::change::{self, NewChange};
use lightnote_core::models::{EntityType, Note, Operation};
use lightnote_core::{outbox, repo};
use lightnote_core::util::{now_ms, uuid_v7};
use lightnote_slice::Client;
use std::io::Write;
use std::time::Instant;

fn flush() {
    let _ = std::io::stdout().flush();
}
fn mb(bytes: u64) -> f64 {
    bytes as f64 / 1_048_576.0
}
fn db_size(client: &Client) -> u64 {
    std::fs::metadata(client.dir.path().join("data.db")).map(|m| m.len()).unwrap_or(0)
}
fn rss_kb(pid: u32) -> Option<u64> {
    let s = std::fs::read_to_string(format!("/proc/{pid}/status")).ok()?;
    s.lines()
        .find(|l| l.starts_with("VmRSS:"))
        .and_then(|l| l.split_whitespace().nth(1).and_then(|n| n.parse().ok()))
}
fn server_pid() -> Option<u32> {
    let out = std::process::Command::new("pgrep")
        .args(["-n", "-f", "lightnote-server"])
        .output()
        .ok()?;
    String::from_utf8_lossy(&out.stdout).trim().parse().ok()
}

/// 批量 seed：note + change + outbox（无 fts / 无 blob，纯 push 工作量）
fn seed_push_workload(client: &mut Client, n: usize) {
    let now = now_ms();
    let dev = client.core.origin_device_id().to_string();
    const BATCH: usize = 10_000;
    for start in (0..n).step_by(BATCH) {
        let end = (start + BATCH).min(n);
        let tx = client.core.db_mut().tx().expect("tx");
        for i in start..end {
            let id = uuid_v7();
            let note = Note::new(id.clone(), format!("吞吐笔记 {i:06}"), "text".into(), now);
            repo::insert_note(&tx, &note).expect("insert_note");
            let payload = change::note_payload(&note);
            let c = change::record_change(
                &tx,
                &NewChange {
                    origin_device_id: &dev,
                    entity_type: EntityType::Note,
                    entity_id: &id,
                    operation: Operation::Create,
                    base_version: 0,
                    version: 1,
                    payload: &payload,
                },
            )
            .expect("record_change");
            outbox::enqueue(&tx, &c.change_id, now).expect("enqueue");
        }
        tx.commit().expect("commit");
    }
}

fn main() {
    let n: usize = std::env::args()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .expect("usage: throughput <N>");

    println!("╔══════════════════════════════════════════════════════════╗");
    println!("║  Phase 9.2 — Sync Throughput (measure-only)  N={n:<7}    ║", );
    println!("╚══════════════════════════════════════════════════════════╝");
    flush();

    let (server, mut clients) = lightnote_slice::setup(&["A"]);
    let mut a = clients.remove(0);

    // ── Prep：A 生成 N 条 outbox（不计入吞吐） ──
    let t = Instant::now();
    seed_push_workload(&mut a, n);
    println!("Prep  seed {n} outbox        : {:>7.2}s", t.elapsed().as_secs_f64());
    println!("      A outbox before push   : {}", a.outbox_count());
    flush();

    // ── PUSH ──
    let t = Instant::now();
    a.sync();
    let push = t.elapsed();
    let push_changes = n; // 每笔记 1 条 change
    println!("Push  A→server ({push_changes} chg)   : {:>7.2}s   ({:>6.0} chg/s)", push.as_secs_f64(), push_changes as f64 / push.as_secs_f64());
    let rss_srv_after_push = server_pid().and_then(rss_kb);
    println!("      outbox after push      : {}  (必须 0)", a.outbox_count());
    println!("      server RSS             : {} KB", rss_srv_after_push.unwrap_or(0));
    flush();

    // ── PULL（全新空 B 客户端首同步：pull + apply + FTS 构建）──
    let mut b = Client::new(&server, "B");
    let rss_cli_before = rss_kb(std::process::id());
    let t = Instant::now();
    b.sync();
    let pull = t.elapsed();
    let rss_cli_after = rss_kb(std::process::id());
    let b_count = b.core.list_notes(None, false).expect("list").len();
    println!("Pull  server→B ({push_changes} chg)   : {:>7.2}s   ({:>6.0} chg/s)", pull.as_secs_f64(), push_changes as f64 / pull.as_secs_f64());
    println!("      B notes / cursor       : {b_count} / {}  (notes 必须 == {n})", b.cursor());
    println!("      client RSS before/after: {} / {} KB", rss_cli_before.unwrap_or(0), rss_cli_after.unwrap_or(0));
    flush();

    // ── 正确性断言 ──
    assert_eq!(a.outbox_count(), 0, "A outbox 必须清空");
    assert_eq!(b_count, n, "B 必须收到全部 {n} 条笔记（无丢失）");
    // 无重复：B 的笔记数恰为 N（list_notes(None,false) 返回根级，seed 全是根级）
    // FTS 可搜（确认 pull 阶段 FTS 已构建）
    let hits = b.core.search("吞吐", 10).expect("search");
    assert!(!hits.is_empty(), "B 的 FTS 必须可搜到「吞吐」(确认 pull 阶段 FTS 已构建)");
    println!("Correctness: outbox=0 ✅  B notes={n} ✅  FTS search hits={} ✅", hits.len());

    // ── 大小 ──
    println!("Size   A data.db / B data.db : {:.2} / {:.2} MB", mb(db_size(&a)), mb(db_size(&b)));
    println!();

    // ── 增量幂等：B 再同步不应改变数据 ──
    let cursor = b.cursor();
    b.sync();
    assert_eq!(b.cursor(), cursor, "无新变更，B cursor 不应前进");
    assert_eq!(b.core.list_notes(None, false).expect("list").len(), n);
    println!("Idempotent re-sync: cursor 不变 ✅");

    println!("\ndone N={n}");
    drop(server);
}
