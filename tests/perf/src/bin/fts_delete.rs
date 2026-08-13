//! Phase 8.2 — FTS DELETE 模式根因验证（measure-only，不修改生产代码）
//!
//! 假设：生产 fts::sync_note 的 `DELETE FROM note_fts WHERE note_id = ?` 中 note_id 是
//! UNINDEXED 列（schema/client.sql:69），FTS5 对 UNINDEXED 列的 WHERE 必须全表扫描 →
//! 每次调用 O(n) → 总量 O(n²)。而按 rowid 删除是 O(log n)。
//!
//! 本探针在隔离表上复现两种 DELETE 模式，对照其增长曲线。
//!     cargo run --release --bin fts_delete unindexed 30000
//!     cargo run --release --bin fts_delete rowid 30000

use lightnote_core::commands::Core;
use std::io::Write;
use std::time::Instant;

fn flush() {
    let _ = std::io::stdout().flush();
}

fn main() {
    let mode = std::env::args().nth(1).unwrap_or_else(|| "unindexed".into());
    let n: usize = std::env::args()
        .nth(2)
        .and_then(|s| s.parse().ok())
        .unwrap_or(30_000);
    const WINDOW: usize = 5_000;

    let tmp = tempfile::tempdir().expect("tmp");
    let core = Core::open(
        tmp.path().join("d.db"),
        tmp.path().join("blobs"),
        "probe",
        "probe",
    )
    .expect("open");
    let conn = core.db().connection();

    match mode.as_str() {
        "unindexed" => {
            conn.execute("CREATE VIRTUAL TABLE ft USING fts5(id UNINDEXED, content)", [])
                .expect("create");
        }
        "rowid" => {
            conn.execute("CREATE VIRTUAL TABLE ft USING fts5(content)", []).expect("create");
        }
        other => panic!("unknown mode: {other} (use unindexed | rowid)"),
    }

    println!("fts_delete: mode={mode} n={n} window={WINDOW}");
    println!("window       | win(s) | per-doc(ms)");
    flush();

    let mut total = 0usize;
    let mut first: Option<f64> = None;
    let mut last = 0.0;
    while total < n {
        let end = (total + WINDOW).min(n);
        let t = Instant::now();
        conn.execute_batch("BEGIN").expect("begin");
        for i in total..end {
            let id_str = format!("id-{i}");
            let content = format!("content {i} some text to index");
            match mode.as_str() {
                "unindexed" => {
                    // 复刻生产 fts::sync_note：DELETE WHERE <UNINDEXED 列>，再 INSERT
                    conn.execute("DELETE FROM ft WHERE id = ?1", (id_str.as_str(),)).expect("delete");
                    conn.execute("INSERT INTO ft (id, content) VALUES (?1, ?2)", (id_str.as_str(), content.as_str()))
                        .expect("insert");
                }
                "rowid" => {
                    // 修复方案：按 rowid 删除（O(log n)）
                    conn.execute("DELETE FROM ft WHERE rowid = ?1", (i as i64,)).expect("delete");
                    conn.execute("INSERT INTO ft (rowid, content) VALUES (?1, ?2)", (i as i64, content.as_str()))
                        .expect("insert");
                }
                _ => unreachable!(),
            }
        }
        conn.execute_batch("COMMIT").expect("commit");
        let win = t.elapsed().as_secs_f64();
        let per_doc = win * 1000.0 / (end - total) as f64;
        if first.is_none() {
            first = Some(per_doc);
        }
        last = per_doc;
        println!("{total:>6}..{end:<6} | {win:>6.2}s | {per_doc:>8.3}");
        flush();
        total = end;
    }
    if let Some(f) = first {
        println!("growth last/first = {:.2}", last / f);
    }
    println!("done");
    drop(core);
}
