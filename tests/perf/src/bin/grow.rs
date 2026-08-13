//! Phase 8.1 — 增长型 per-step profiling（measure-only）
//!
//! 在同一个 DB 上按 5K 窗口递增 seed，累计每步（note/change/outbox/fts）耗时。
//! 比较各窗口的每步斜率，定位 per-op 成本随表增长而上升的根因。
//! 用法：
//!     cargo run --release --bin grow fts 30000     # 含 FTS，到 30K
//!     cargo run --release --bin grow nofts 30000   # 不含 FTS，对照

use lightnote_core::change::{self, NewChange};
use lightnote_core::db::Tx;
use lightnote_core::models::{EntityType, Note, Operation};
use lightnote_core::{fts, outbox, repo};
use lightnote_core::util::{now_ms, uuid_v7};
use lightnote_slice::Client;
use std::io::Write;
use std::time::{Duration, Instant};

fn flush() {
    let _ = std::io::stdout().flush();
}
fn ms(d: Duration) -> f64 {
    d.as_secs_f64() * 1000.0
}

fn main() {
    let mode = std::env::args().nth(1).unwrap_or_else(|| "fts".into());
    let with_fts = mode != "nofts";
    let target: usize = std::env::args()
        .nth(2)
        .and_then(|s| s.parse().ok())
        .unwrap_or(30_000);
    const WINDOW: usize = 5_000;

    let (server, _) = lightnote_slice::setup(&[]);
    let mut client = Client::new(&server, "grow");

    println!("grow: mode={mode} with_fts={with_fts} target={target} window={WINDOW}");
    println!("window | total win(s) |  note | change | outbox |   fts");
    flush();

    let now = now_ms();
    let dev = client.core.origin_device_id().to_string();
    let mut total = 0usize;
    while total < target {
        let end = (total + WINDOW).min(target);
        let mut t_note = Duration::ZERO;
        let mut t_change = Duration::ZERO;
        let mut t_outbox = Duration::ZERO;
        let mut t_fts = Duration::ZERO;
        let win_start = Instant::now();

        let tx = client.core.db_mut().tx().expect("tx");
        for i in total..end {
            let id = uuid_v7();
            let note = Note::new(id.clone(), format!("笔记 {i:06} · 架构同步数据库"), "text".into(), now);

            let t = Instant::now();
            repo::insert_note(&tx, &note).expect("insert_note");
            t_note += t.elapsed();

            let payload = change::note_payload(&note);
            let t = Instant::now();
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
            t_change += t.elapsed();

            let t = Instant::now();
            outbox::enqueue(&tx, &c.change_id, now).expect("enqueue");
            t_outbox += t.elapsed();

            if with_fts {
                let t = Instant::now();
                fts::sync_note(&tx, &id).expect("fts");
                t_fts += t.elapsed();
            }
        }
        commit(tx);
        let win = win_start.elapsed();

        println!(
            "{total:>6}..{end:<6} | {:>6.2}s     | {:>5.0} | {:>6.0} | {:>6.0} | {:>5.0}",
            win.as_secs_f64(),
            ms(t_note),
            ms(t_change),
            ms(t_outbox),
            ms(t_fts),
        );
        flush();
        total = end;
    }
    println!("done");
    drop(server);
}

fn commit(tx: Tx<'_>) {
    tx.commit().expect("commit");
}
