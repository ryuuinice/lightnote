//! Phase 8.1 — Seed scaling diagnosis (measure-only, no core edits)
//!
//! 用 public 原语（repo / change / outbox / fts）在 fixture 侧重建 seed 路径并分段计时，
//! 判定生产 seed 路径的复杂度类别、定位最慢成分、量化事务粒度与 FTS 占比，
//! 并验证「单事务 bulk fixture」能否让 100K 数据集可测。
//!
//! 运行：cargo run --release --bin diagnose

use lightnote_core::change::{self, NewChange};
use lightnote_core::models::{Branch, EntityType, Note, Operation};
use lightnote_core::{fts, outbox, repo, util::{now_ms, uuid_v7}};
use lightnote_slice::{Client, Server};
use std::time::{Duration, Instant};

fn ms(d: Duration) -> f64 {
    d.as_secs_f64() * 1000.0
}
fn sec(d: Duration) -> f64 {
    d.as_secs_f64()
}

fn main() {
    println!("╔══════════════════════════════════════════════════════════╗");
    println!("║  Phase 8.1 — Seed Scaling Diagnosis (measure-only)      ║");
    println!("╚══════════════════════════════════════════════════════════╝");

    let (server, _) = lightnote_slice::setup(&[]);

    phase_a_production_slope(&server);
    phase_b_tx_granularity(&server);
    phase_c_fts_isolation(&server);
    phase_d_bulk_fixture_scaling(&server);

    println!("\n诊断完成。");
    drop(server);
}

/// Phase A —— 生产路径 per-block 斜率：判定 O(1)/O(log n)/O(n) 的 per-op 复杂度
fn phase_a_production_slope(server: &Server) {
    println!("\n── Phase A: 生产路径 per-block 斜率（create_note+save_content） ──");
    let n = 5_000;
    let block = 1_000;
    let mut client = Client::new(server, "phaseA");
    println!("Seeding {n} notes via 生产 API，每 {block} 条一段…");

    let mut blocks: Vec<Duration> = Vec::new();
    let mut t = Instant::now();
    for i in 0..n {
        let id = client
            .core
            .create_note("root", &format!("A 笔记 {i:05} · 架构同步"), "text")
            .expect("create")
            .note_id;
        client
            .core
            .save_content(&id, &format!("A 正文 {i:05}：Rust SQLite 同步性能诊断。"))
            .expect("save");
        if (i + 1) % block == 0 {
            blocks.push(t.elapsed());
            t = Instant::now();
            print!("  block {} ", blocks.len());
            flush();
        }
    }
    println!();
    println!("  每段（{block} 条）耗时：");
    for (i, b) in blocks.iter().enumerate() {
        println!("    block {}: {:.0} ms", i + 1, ms(*b));
    }
    if blocks.len() >= 2 {
        let first = ms(blocks[0]);
        let last = ms(blocks[blocks.len() - 1]);
        let ratio = last / first;
        println!("  斜率 last/first = {:.2}", ratio);
        println!("  判定：");
        println!("    ratio ≈ 1.0  → per-op O(1)（纯线性）");
        println!("    ratio ≈ 1.2~1.5 → per-op O(log n)（B-tree 增长，正常）");
        println!("    ratio ≈ k（段数） → per-op O(n)（全表扫描，O(n²) 总量，需修）");
        println!("  实测 ratio={:.2}（{} 段）→ {}", ratio, blocks.len(), classify(ratio, blocks.len()));
    }
}

fn classify(ratio: f64, n_blocks: usize) -> &'static str {
    let k = n_blocks as f64;
    if ratio < 1.3 {
        "O(1) per-op（线性总量）"
    } else if ratio < k * 0.5 {
        "O(log n) per-op（正常 B-tree 增长）"
    } else {
        "⚠ 疑似 O(n) per-op（O(n²) 总量）"
    }
}

/// Phase B —— 事务粒度：bulk 单事务 vs 每笔记一次事务（含 FTS）
fn phase_b_tx_granularity(server: &Server) {
    println!("\n── Phase B: 事务粒度（full 原语 + FTS，create 侧） ──");
    let n = 4_000;
    let mut c_bulk = Client::new(server, "phaseB-bulk");
    let t = Instant::now();
    seed_reconstructed(&mut c_bulk.core, n, true, true);
    let bulk = t.elapsed();
    println!("  bulk 单事务  ×{n}: {:.2}s", sec(bulk));

    let mut c_pn = Client::new(server, "phaseB-pernote");
    let t = Instant::now();
    seed_reconstructed(&mut c_pn.core, n, true, false);
    let per_note = t.elapsed();
    println!("  per-note 事务 ×{n}: {:.2}s", sec(per_note));
    println!("  粒度开销倍数: {:.1}× （per-note-tx / bulk-tx）", sec(per_note) / sec(bulk));
}

/// Phase C —— FTS 占比：bulk 含 FTS vs 不含 FTS
fn phase_c_fts_isolation(server: &Server) {
    println!("\n── Phase C: FTS 占比（bulk 单事务，create 侧） ──");
    let n = 4_000;
    let mut c_fts = Client::new(server, "phaseC-fts");
    let t = Instant::now();
    seed_reconstructed(&mut c_fts.core, n, true, true);
    let with = t.elapsed();
    println!("  bulk 含 FTS   ×{n}: {:.2}s", sec(with));

    let mut c_no = Client::new(server, "phaseC-nofts");
    let t = Instant::now();
    seed_reconstructed(&mut c_no.core, n, false, true);
    let without = t.elapsed();
    println!("  bulk 不含 FTS ×{n}: {:.2}s", sec(without));
    println!("  FTS 净开销: {:.2}s（{:.0}%）", sec(with) - sec(without),
        100.0 * (sec(with) - sec(without)) / sec(with));
}

/// Phase D —— bulk fixture 可扩展性 + 100K 外推
fn phase_d_bulk_fixture_scaling(server: &Server) {
    println!("\n── Phase D: bulk fixture 可扩展性（create 侧，含 FTS） ──");
    for &n in &[1_000usize, 5_000, 10_000] {
        let mut c = Client::new(server, &format!("phaseD-{n}"));
        let t = Instant::now();
        seed_reconstructed(&mut c.core, n, true, true);
        let d = t.elapsed();
        let per = ms(d) / n as f64;
        println!("  bulk ×{n:>6}: {:>6.2}s  ({:.3} ms/note)", sec(d), per);
    }
    // 粗略外推 100K（假设接近线性；若 super-linear 则实际更慢）
    println!("  → 100K 外推需实测验证（bulk fixture 工程目标 < 60s）");
}

/// 用 public 原语重建「create 侧」seed：note + branch + change + outbox + (可选 FTS)。
/// `bulk=true` 单事务提交；`bulk=false` 每笔记一次事务（模拟生产粒度，但无 save/blob 侧）。
fn seed_reconstructed(core: &mut lightnote_core::commands::Core, n: usize, with_fts: bool, bulk: bool) {
    let now = now_ms();
    let dev = "bench-device".to_string();
    if bulk {
        let tx = core.db_mut().tx().expect("tx");
        for i in 0..n {
            seed_one_note_in_tx(&tx, i, now, &dev, with_fts);
        }
        tx.commit().expect("commit");
    } else {
        for i in 0..n {
            let tx = core.db_mut().tx().expect("tx");
            seed_one_note_in_tx(&tx, i, now, &dev, with_fts);
            tx.commit().expect("commit");
        }
    }
}

fn seed_one_note_in_tx(
    tx: &lightnote_core::db::Tx<'_>,
    i: usize,
    now: i64,
    dev: &str,
    with_fts: bool,
) {
    let id = uuid_v7();
    let title = format!("bulk 笔记 {i:06} · 架构同步数据库");
    let note = Note::new(id.clone(), title, "text".into(), now);
    repo::insert_note(tx, &note).expect("insert_note");
    // 一条 branch（挂到 root 下，产生与生产等量的行）
    let branch = Branch::new(uuid_v7(), "root".into(), id.clone(), i as i64, now);
    repo::insert_branch(tx, &branch).expect("insert_branch");
    let payload = change::note_payload(&note);
    let c = change::record_change(
        tx,
        &NewChange {
            origin_device_id: dev,
            entity_type: EntityType::Note,
            entity_id: &id,
            operation: Operation::Create,
            base_version: 0,
            version: 1,
            payload: &payload,
        },
    )
    .expect("record_change");
    outbox::enqueue(tx, &c.change_id, now).expect("enqueue");
    if with_fts {
        fts::sync_note(tx, &id).expect("fts");
    }
}

fn flush() {
    use std::io::Write;
    let _ = std::io::stdout().flush();
}
