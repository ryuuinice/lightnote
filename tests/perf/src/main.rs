//! LightNote Phase 8 —— 性能基线测量器（measure-only）
//!
//! 严格遵循“先测量，再优化”：本程序只调用 lightnote_core 公共 API 与 Go 服务端，
//! 不修改同步核心。把架构 §44 中可在无头环境测得的目标固化为 Gate。
//!
//! Tauri/Vue/IPC/UI 冷启动与渲染指标留待 Windows 显示环境（Deferred，与 GUI-001~008 同批）。
//!
//! 运行（必须 release）：
//!     cargo run --release                # 默认 Dataset-S（1000 notes）
//!     cargo run --release -- 10000       # Dataset-M（10000 notes）
//!     cargo run --release -- 100000      # Dataset-L（100000 notes，可选）

use lightnote_core::change::{self, NewChange};
use lightnote_core::commands::Core;
use lightnote_core::models::{EntityType, Note, Operation};
use lightnote_core::{fts, outbox, repo};
use lightnote_core::util::{now_ms, uuid_v7};
use lightnote_slice::{Client, Server};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

const DEFAULT_N: usize = 1_000;

fn main() {
    let n: usize = std::env::args()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .unwrap_or(DEFAULT_N);

    println!("╔══════════════════════════════════════════════════════════╗");
    println!("║  LightNote Phase 8 — Performance Baseline (measure-only) ║");
    println!("╚══════════════════════════════════════════════════════════╝");
    println!("Dataset: {n} notes (CJK content + 内容寻址 blob)\n");
    flush();

    let (server, mut clients) = lightnote_slice::setup(&["A", "B"]);
    let mut a = clients.remove(0);
    let mut b = clients.remove(0);

    let gates = Gates::default();

    // ── 1. Seed（bulk fixture：单事务批量生成，仅测试用；生产 seed 行为见 diagnose） ──
    let t = Instant::now();
    let ids = seed_bulk_realistic(&mut a, n);
    let seed = t.elapsed();
    println!("Seed  bulk fixture ×{n:>7} : {:>10.2?}  ({} notes, {} outbox)", seed, ids.len(), a.outbox_count());
    flush();

    // ── 2. SQLite DB 文件大小 ──
    let db_size = std::fs::metadata(a.dir.path().join("data.db")).map(|m| m.len()).unwrap_or(0);
    println!("Size  SQLite data.db      : {:>10.2} MB", db_size as f64 / 1_048_576.0);

    // ── 3. Tree 首屏（list_notes 根级） ──
    let t = Instant::now();
    let tree = a.core.list_notes(None, false).expect("list_notes");
    let tree_load = t.elapsed();
    println!("\nTree  list_notes(root)    : {:>10.2?}  [{} 条]", tree_load, tree.len());
    gates.check("Tree 加载 < 200ms", tree_load < Duration::from_millis(200));

    // ── 4. Note 打开（get_note + get_content，平均 K 次） ──
    let k = 50.min(n);
    let t = Instant::now();
    for id in ids.iter().take(k) {
        let _ = a.core.get_note(id).expect("get_note");
        let _ = a.core.get_content(id).expect("get_content");
    }
    let note_open = t.elapsed() / k as u32;
    println!("Open  get_note+content avg : {:>10.2?}  (over {k})", note_open);

    // ── 5. Note 保存（单次编辑，平均 K 次） ──
    let t = Instant::now();
    for (i, id) in ids.iter().take(k).enumerate() {
        a.core
            .save_content(id, &format!("编辑 {i} 内容修改 — 性能测试"))
            .expect("save_content");
    }
    let note_save = t.elapsed() / k as u32;
    println!("Save  save_content avg     : {:>10.2?}  (over {k})", note_save);
    gates.check("Note 保存 < 50ms", note_save < Duration::from_millis(50));

    // ── 6. FTS 中文/拉丁搜索 ──
    println!("\nFTS   query (limit=50):");
    let queries = ["架构", "项目", "同步", "数据库", "性能", "Rust", "SQLite", "本地优先", "中文搜索无命中"];
    let mut fts_worst = Duration::ZERO;
    for q in queries {
        let t = Instant::now();
        let hits = a.core.search(q, 50).expect("search");
        let d = t.elapsed();
        if d > fts_worst {
            fts_worst = d;
        }
        println!("      «{q:<8}» {:>8.2?}  ({} hits)", d, hits.len());
    }
    println!("FTS   worst query          : {:>10.2?}", fts_worst);
    gates.check("FTS 搜索 < 200ms", fts_worst < Duration::from_millis(200));

    // ── 7. 同步：Push 全量 / Pull 全量（信息项；大样本下吞吐专项测，这里跳过） ──
    println!("\nSync:");
    flush();
    if n <= 50_000 {
        let t = Instant::now();
        a.sync();
        let push_full = t.elapsed();
        println!("      A push 全量 outbox   : {:>10.2?}  (outbox→0: {})", push_full, a.outbox_count());
        flush();

        let t = Instant::now();
        b.sync();
        let pull_full = t.elapsed();
        let b_count = b.core.list_notes(None, false).expect("list").len();
        println!("      B pull 全量数据集    : {:>10.2?}  ({} notes, cursor={})", pull_full, b_count, b.cursor());
        flush();
    } else {
        // 大样本下先把 A 的 outbox 清空，便于增量门控干净；不计时（吞吐专项见独立 benchmark）
        a.sync();
        b.sync();
        println!("      (全量 push/pull 在 N={n} 跳过计时：线性外推 > 10min；增量门控仍测)");
        flush();
    }

    // ── 8. 增量同步（§44「普通同步 < 1s」的真实语义） ──
    let extra = a.core.create_note("root", "增量同步测试", "text").expect("create").note_id;
    a.core.save_content(&extra, "增量内容").expect("save");
    let t = Instant::now();
    a.sync();
    b.sync();
    let incremental = t.elapsed();
    println!("      增量同步 A→B (1 note): {:>10.2?}", incremental);
    gates.check("普通同步 < 1s", incremental < Duration::from_secs(1));

    // ── 9. 冷启动 Core::open（引擎代理；完整 App 冷启动留待 Windows） ──
    // 用 DB 副本测量打开路径，避免干扰在线连接。
    let cold_tmp = tempfile::tempdir().expect("cold tmp");
    let cold_db = cold_tmp.path().join("cold.db");
    std::fs::copy(a.dir.path().join("data.db"), &cold_db).expect("copy db");
    let t = Instant::now();
    let _cold_core = Core::open(&cold_db, cold_tmp.path().join("blobs"), "cold-client", "cold-device")
        .expect("cold open");
    let cold = t.elapsed();
    println!("\nCold  Core::open (proxy)   : {:>10.2?}  [完整 App 冷启动 deferred]", cold);

    // ── 汇总 ──
    println!("\n────────────────────────────────────────");
    gates.summary();
    println!("────────────────────────────────────────");
    println!("基线已捕获。放大: cargo run --release -- 10000");
    drop(_cold_core);
    drop(server);
}

/// Bulk fixture（仅测试用）：分批事务批量生成 N 篇带 CJK 标题的笔记。
///
/// 生产 seed 走 Core::create_note + save_content（每笔记一次事务 + fsync + blob 文件 I/O），
/// 经 Phase 8.1 诊断证实其开销来自 per-note 提交与 blob I/O，而非算法。
/// 本 fixture 用分批事务（每批 10K → 仅 ~N/10K 次 fsync）承载写入，且不逐笔记 blob
/// （blob 规模属独立维度，专项测）。产物 = note + change + outbox + FTS(title)，
/// 足以驱动 Tree / FTS-title / Save / Sync / 增量 等 §44 门控。
fn seed_bulk_realistic(client: &mut Client, n: usize) -> Vec<String> {
    const POOL: &[&str] = &[
        "数据库", "前端", "后端", "架构", "同步", "性能", "搜索", "笔记", "知识库", "本地优先",
        "Rust", "SQLite", "Tauri", "Vue", "冲突", "游标", "变更", "附件",
    ];
    let now = now_ms();
    let dev = client.core.origin_device_id().to_string();
    let mut ids = Vec::with_capacity(n);

    const BATCH: usize = 10_000;
    for batch_start in (0..n).step_by(BATCH) {
        let batch_end = (batch_start + BATCH).min(n);
        let tx = client.core.db_mut().tx().expect("tx");
        for i in batch_start..batch_end {
            let tag = POOL[i % POOL.len()];
            let title = format!("笔记 {i:05} · {tag} 项目架构");
            let id = uuid_v7();
            let note = Note::new(id.clone(), title, "text".into(), now);
            repo::insert_note(&tx, &note).expect("insert_note");
            // 与生产 create_note("root", ...) 一致：root 级不创建 branch（否则 list_notes(None) 不命中）
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
            fts::sync_note(&tx, &id).expect("fts");
            ids.push(id);
        }
        tx.commit().expect("commit");
        println!("   …committed batch @{} (total {})", batch_start, ids.len());
        flush();
    }
    ids
}

#[derive(Default)]
struct Gates {
    passed: AtomicUsize,
    total: AtomicUsize,
}

impl Gates {
    fn check(&self, label: &str, pass: bool) {
        self.total.fetch_add(1, Ordering::Relaxed);
        if pass {
            self.passed.fetch_add(1, Ordering::Relaxed);
        }
        println!("   GATE [{}] {}", label, if pass { "PASS ✅" } else { "FAIL ❌" });
    }
    fn summary(&self) {
        let p = self.passed.load(Ordering::Relaxed);
        let t = self.total.load(Ordering::Relaxed);
        println!("Gates: {p}/{t} passed");
    }
}

// 抑制未使用警告（Server 占位保持生命周期）
#[allow(dead_code)]
fn _keep_server(s: Server) {
    drop(s);
}

fn flush() {
    use std::io::Write;
    let _ = std::io::stdout().flush();
}
