//! Phase 8.2 — FTS 架构专项探针（measure-only，不修改任何生产代码）
//!
//! 在隔离的 probe_fts 表上，用不同 tokenization 策略与写入粒度做对照实验，
//! 定位 FTS5 超线性增长的具体根因（高频 token / 分词 / 写入粒度）。
//!
//! 用法：
//!     cargo run --release --bin fts_probe <mode> <n> <batch>
//! mode:  cjk_space | cjk_raw | english | repeated | random
//! batch: 提交粒度（每 batch 行一次事务；=1 表示逐行 auto-commit）。默认 100000
//!
//! 例：
//!     cargo run --release --bin fts_probe cjk_space 30000
//!     cargo run --release --bin fts_probe repeated 30000
//!     cargo run --release --bin fts_probe random 30000
//!     cargo run --release --bin fts_probe cjk_space 10000 1   # 逐行提交

use lightnote_core::commands::Core;
use lightnote_core::util::is_cjk;
use std::io::Write;
use std::time::Instant;

fn flush() {
    let _ = std::io::stdout().flush();
}

/// 复刻生产 fts::cjk_space：CJK 字符间插空格，让 unicode61 逐字成 token
fn cjk_space(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 8);
    let mut prev_cjk = false;
    for c in text.chars() {
        if is_cjk(c) {
            if prev_cjk {
                out.push(' ');
            }
            out.push(c);
            prev_cjk = true;
        } else {
            out.push(c);
            prev_cjk = false;
        }
    }
    out
}

/// 每篇文档的基准短语（CJK + 标点 + 数字），各 mode 在其上做不同处理
fn base_phrase(i: usize) -> String {
    format!("笔记 {i:06} 数据库 项目架构 同步性能 笔记系统")
}

/// 从 ~2048 个 CJK 字符里按 i 取 10 个低重叠字符（低频 token 集）
fn random_cjk(i: usize) -> String {
    const POOL: u32 = 2048;
    const BASE: u32 = 0x4E00;
    const STEP: u32 = 2654435761; // 2^32 的黄金分割常数，散列充分
    let mut s = String::new();
    for j in 0..10u32 {
        let idx = (i as u32).wrapping_add(j.wrapping_mul(STEP)) % POOL;
        if let Some(ch) = char::from_u32(BASE + idx) {
            s.push(ch);
            s.push(' ');
        }
    }
    s
}

fn content_for(mode: &str, i: usize) -> String {
    match mode {
        "cjk_space" => cjk_space(&base_phrase(i)),
        "cjk_raw" => base_phrase(i), // 不加空格：unicode61 把连续 CJK 视作单 token
        "english" => format!("note {i:06} database architecture synchronization performance rust sqlite fts"),
        "repeated" => "数据库 同步 笔记 ".repeat(8), // 极高频重复 token
        "random" => random_cjk(i),                    // 低频、高基数 token
        other => panic!("unknown mode: {other}"),
    }
}

fn main() {
    let mode = std::env::args().nth(1).unwrap_or_else(|| "cjk_space".into());
    let n: usize = std::env::args()
        .nth(2)
        .and_then(|s| s.parse().ok())
        .unwrap_or(30_000);
    let batch: usize = std::env::args()
        .nth(3)
        .and_then(|s| s.parse().ok())
        .unwrap_or(100_000);
    const WINDOW: usize = 5_000;

    let tmp = tempfile::tempdir().expect("tmp");
    let core = Core::open(
        tmp.path().join("probe.db"),
        tmp.path().join("blobs"),
        "probe",
        "probe",
    )
    .expect("open");
    let conn = core.db().connection();
    conn.execute("CREATE VIRTUAL TABLE probe_fts USING fts5(content)", [])
        .expect("create probe_fts");

    println!("fts_probe: mode={mode} n={n} batch={} window={WINDOW}", if batch == 1 { "per-row".into() } else { batch.to_string() });
    println!("window       | win(s) | per-doc(ms)");
    flush();

    let per_row = batch == 1;
    let mut total = 0usize;
    let mut first: Option<f64> = None;
    let mut last: f64 = 0.0;
    while total < n {
        let end = (total + WINDOW).min(n);
        let t = Instant::now();
        if per_row {
            for i in total..end {
                let c = content_for(&mode, i);
                conn.execute("INSERT INTO probe_fts (content) VALUES (?1)", [&c])
                    .expect("insert");
            }
        } else {
            conn.execute_batch("BEGIN").expect("begin");
            for i in total..end {
                let c = content_for(&mode, i);
                conn.execute("INSERT INTO probe_fts (content) VALUES (?1)", [&c])
                    .expect("insert");
            }
            conn.execute_batch("COMMIT").expect("commit");
        }
        let win = t.elapsed().as_secs_f64();
        let per_doc = win * 1000.0 / (end - total) as f64;
        if first.is_none() {
            first = Some(per_doc);
        }
        last = per_doc;
        println!("{total:>6}..{end:<6} | {win:>5.2}s | {per_doc:>7.3}", );
        flush();
        total = end;
    }
    if let Some(f) = first {
        println!("growth last/first = {:.2}", last / f);
    }
    println!("done");
    drop(core);
}
