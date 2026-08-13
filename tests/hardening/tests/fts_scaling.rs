//! Phase 8.4 防回归：sync_note 单次耗时不随 FTS 表规模 O(n) 增长。
//!
//! 根因回顾（Phase 8.2 定位）：fts::sync_note 曾用 `DELETE FROM note_fts WHERE note_id=?`，
//! 而 note_id 为 UNINDEXED → 每次 DELETE 全表扫描 → 单次调用 O(n)。本测试在 1K 与 8K
//! FTS 规模下采样单次 sync_note 中位耗时，断言比值有界（O(log n) 约 1.x；旧 bug 约 8×）。
//! 目的不是锁死毫秒数，而是捕获未来任何「按非 rowid 键删除/定位 FTS」的回退。

use lightnote_core::change::{self, NewChange};
use lightnote_core::commands::Core;
use lightnote_core::models::{EntityType, Note, Operation};
use lightnote_core::util::{now_ms, uuid_v7};
use lightnote_core::{fts, outbox, repo};
use std::time::Instant;

fn bulk_seed(core: &mut Core, start: usize, count: usize) -> Vec<String> {
    let now = now_ms();
    let dev = core.origin_device_id().to_string();
    let mut ids = Vec::with_capacity(count);
    let tx = core.db_mut().tx().expect("tx");
    for i in start..start + count {
        let id = uuid_v7();
        let note = Note::new(id.clone(), format!("笔记 {i:05} 数据库架构同步"), "text".into(), now);
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
        fts::sync_note(&tx, &id).expect("fts");
        ids.push(id);
    }
    tx.commit().expect("commit");
    ids
}

/// 在单个事务内采样 sync_note，剥离 auto-commit fsync 干扰，只测 FTS 逻辑耗时。
/// 否则 fsync（~ms 级）会淹没 FTS 逻辑（µs 级），让 O(n) 回退在中小规模下漏检。
fn median_sync_note_us(core: &mut Core, ids: &[String], samples: usize) -> f64 {
    let mut times: Vec<f64> = Vec::with_capacity(samples);
    let tx = core.db_mut().tx().expect("tx");
    for id in ids.iter().take(samples) {
        let t = Instant::now();
        fts::sync_note(&tx, id).expect("sync_note");
        times.push(t.elapsed().as_micros() as f64);
    }
    tx.rollback();
    times.sort_by(|a, b| a.partial_cmp(b).unwrap());
    times[times.len() / 2]
}

#[test]
fn sync_note_does_not_scale_with_fts_size() {
    let tmp = tempfile::tempdir().expect("tmp");
    let mut core = Core::open(
        tmp.path().join("d.db"),
        tmp.path().join("blobs"),
        "client-reg",
        "device-reg",
    )
    .expect("open");

    let ids_1k = bulk_seed(&mut core, 0, 1_000);
    let med_1k = median_sync_note_us(&mut core, &ids_1k, 200);

    let ids_8k = bulk_seed(&mut core, 1_000, 7_000); // 累计到 8K
    let med_8k = median_sync_note_us(&mut core, &ids_8k, 200);

    let ratio = med_8k / med_1k;
    println!("sync_note median @1K={med_1k:.1}µs  @8K={med_8k:.1}µs  ratio={ratio:.2}");
    // O(log n) → ratio ≈ 1.x；旧 UNINDEXED-DELETE bug → ratio ≈ 8。4.0 留足余量。
    assert!(
        ratio < 4.0,
        "sync_note 出现随 FTS 规模的超线性增长（疑似回退到非 rowid 键删除）：@1K={med_1k}µs @8K={med_8k}µs ratio={ratio:.2}",
    );
}
