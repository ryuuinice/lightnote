# Changelog

本文件记录 LightNote v1.1 重构（Rust + Go + Tauri）的阶段性基线。
架构基线见 `docs/architecture/LightNote技术架构设计v1.1.md`；开发计划见 `docs/architecture/LightNoteV1.2AI多代理开发计划.md`。

## v1.1-phase9.2 — 同步大规模吞吐基线 + GUI 验收协议

Phase 9 第一项可自动化交付：服务端/同步大规模吞吐基准（measure-only）+ Deferred GUI 验收协议。
正确性、资源、性能三维度在 1K/10K/100K 全部验证。

### 吞吐结果（tests/perf/src/bin/throughput.rs）

| 场景 | 1K | 10K | 100K |
|---|---:|---:|---:|
| Push (chg/s) | 775 | 647 | 709 |
| Pull/Apply/FTS (chg/s) | 2649 | 9195 | 10521 |
| **Pull 总时（新设备首同步）** | 0.4s | 1.1s | **9.5s** |
| Push 总时 | 1.3s | 15.5s | 141s |
| server RSS / client RSS | 25/11MB | 30/12MB | **31/12MB（平稳）** |

- **新设备首同步 100K Notes = 9.5 秒**（10521 chg/s，含 Apply + FTS 构建）—— 真实大数据场景达标。
- 内存零膨胀（1K→100K server/client RSS 平稳）。
- 正确性 P0：0 丢失 / 0 重复 / cursor 正确 / outbox 清空 / FTS 可搜 / 幂等。

### Deferred：PERF-001（Server Push per-change fsync）

100K Push = 141s（~709 chg/s，吞吐平稳）。瓶颈为 `server/internal/sync/push.go` 的 `PushService` 逐条
`Committer.Commit`，每条一个事务/一次 fsync。**非正常用户路径**（用户每次编辑只推几条 change），
仅影响 bulk 导入/迁移/大规模离线回灌。**Status: Deferred (P2)**；后续批量 server-side commit 时重新 benchmark。

### Deferred：GUI-001~008（Windows 真机）

`docs/acceptance/gui-acceptance.md`：完整 Deferred 验收协议（登录/双向 Pull/删除/冲突/Blob/离线恢复/重启恢复）
+ 双设备 E2E 执行包。本无头环境不可执行；待 Windows 显示环境一次性验收。

### 已发现 Client 集成缺口（→ Phase 9.2a 跟进）

客户端 Tauri shell（`client/app/src/main.rs`）**未接入 refresh-token**：`auth_login` 仅取 access_token 入内存，
丢弃 refresh_token / device_id；未注册 `auth_refresh`；token 仅内存（重启即失效）。服务端 Phase 6 refresh-token
轮换 + 设备吊销完整，但客户端从未接线 → GUI-008（重启恢复）与 2h token 过期场景受阻。Phase 9.2a 修复。

## v1.1-phase8-performance — 性能基线 + FTS 规模化修复

Phase 8 性能验收：建立 measure-only 性能基线工具，定位并修复一个 FTS 规模化缺陷，
§44 全部 4 个可在无头环境测量的 Gate 在 1K/10K/**100K** 三档均通过。

### 性能 Gate（§44，无头环境实测）

| 指标 | 1K | 10K | 100K | Gate | 状态 |
|---|---:|---:|---:|---|---|
| Tree 加载 list_notes(root) | 1.0ms | 12ms | 105ms | <200ms | ✅ |
| Note 保存 save_content | 6.7ms | 11ms | 6.9ms | <50ms | ✅ |
| FTS 最差查询 | 3.0ms | 15ms | 88ms | <200ms | ✅ |
| 增量同步 A→B (1 note) | 37ms | 38ms | 36ms | <1s | ✅ |

> “10,000 Notes 流畅”硬 Gate 与 “100,000 Notes 可用”软 Gate 均达成。

### FTS 规模化修复（Phase 8.4）

- 根因：`fts::sync_note` 通过 `DELETE FROM note_fts WHERE note_id = ?` 删除旧行，而 `note_id`
  在 schema 中为 `UNINDEXED` → FTS5 无法走倒排索引 → 每次 DELETE 全表扫描 → 单次 O(n)、批量 O(n²)。
  经 Phase 8.2 对照实验隔离（CJK/English/repeated/random 分词均平稳；仅 DELETE 模式超线性）锁定。
- 修复（最小机制）：`note_fts.rowid = notes.rowid`；`sync_note` 先 `SELECT rowid FROM notes WHERE note_id=?`
  （索引），再 `DELETE … WHERE rowid=?`、`INSERT … (rowid,…)`；`trash_empty` 在物理删除前调
  `fts::remove_note` 清理 FTS 行。V4 迁移 DROP + CREATE + rebuild_all。
- 效果：FTS 写入随规模由超线性（30K 增长 12×）变为平稳（~100ms/window，~150× 提升且零增长）；
  `save_content` 在 100K 与 1K 基本一致（6.9ms vs 6.7ms，无规模退化）。
- 防回归测试：`tests/hardening/tests/fts_scaling.rs` 断言 `sync_note` 单次耗时在 1K/8K FTS 规模下
  不出现 O(n) 增长（捕获任何未来“按非 rowid 键删除/定位 FTS”的回退）。

### 性能观察项（非 Gate 阻塞）

At 100K notes, high-frequency CJK FTS queries（如「架构」「项目」）may take ~80ms due to large
posting lists + rank ordering; low-frequency tokens ~5ms. Remains below the <200ms search gate and
is therefore **not** a Phase 8 blocker. Query-side optimization deferred.

### Phase 8 工具（tests/perf/，measure-only）

`lightnote-perf` crate：基线 runner（`main`）+ 三个诊断探针（`diagnose` seed 分段、`grow` per-step 增长、
`fts_probe`/`fts_delete` FTS 根因隔离）。仅调用 `lightnote_core` 公共 API，不改生产代码（除 8.4 已合入的 FTS 修复）。

### 约束遵守

未触动：CJK tokenizer / FTS 引擎 / SQLite driver / Sync Engine / Schema 业务语义 / Repository 架构 / 异步 FTS。

### Deferred（Phase 9）

- Tauri/Vue/UI 冷启动 + 渲染（真实 Windows 环境实测）
- 服务端 Push/Pull 大规模吞吐（100K 全量首同步）
- GUI-001~008 双设备真机 E2E

## v1.1-phase7 — 同步核心稳定基线

首个将 v1.1 重构纳入 Git 的检查点。同步核心、认证、设备、UI 全链路打通；
异常矩阵与离线恢复自动化覆盖到位；全量回归通过。

### 架构

- Local-First：客户端 SQLite 完整副本 + 内容寻址 Blob
- Sync：Entity Change Log + Sync Outbox + Server Sequence（AUTOINCREMENT 严格单调）+ Cursor 增量 Pull
- 冲突：Base Version + Conflict Preservation（删除 vs 修改、双写冲突均保留冲突副本）
- 认证：Access Token (HS256, 2h) + Refresh Token (30 天, SHA-256 哈希存储, 轮换制, 设备吊销级联)
- 服务端：Go + SQLite 单写者；客户端：Rust (lightnote_core) + Tauri + Vue 3

### 已完成（Phases 0–7）

- Phase 0–3：Schema / API / IPC / Change Protocol 契约冻结；Rust core（repo / change / outbox / cursor / engine / apply / FTS / migration）
- Phase 4：Blob 并入 Sync Engine（分片上传 / 断点续传 / 懒下载队列）；Tauri IPC；Rust Command 层
- Phase 5：Tree / Editor / Search / Sync Status / Blob 附件 / Command Palette；Vue 接真实 IPC
- Phase 6：Auth 完整（登录 / 刷新轮换 / 设备吊销传播，修复一个真实安全 bug）；设备管理 UI；Settings；登出
- Phase 7：同步异常矩阵自动化（8 场景）；长时间离线恢复脚本；既有 hardening clippy 清理

### 验证矩阵（全绿）

| 测试组 | 用例数 |
|---|---:|
| Rust Core 单元（client/src-tauri） | 28 |
| Sync Vertical Slice | 9 |
| Sync Hardening（含 Phase 7 异常矩阵 8） | 15 |
| Smoke（端到端） | 10 |
| Go Server 包（api / auth / db / sync） | 4 包 |
| Phase 7 离线恢复脚本（`cargo run --example recover_offline`） | 6 项校验 |

§70 异常场景矩阵覆盖：Push 响应丢失幂等 / Pull 中断 cursor 不推进 / Pull 应用失败事务回滚 /
长期离线增量 Pull / Blob 续传 / Blob Hash Reject / 服务端序列 gap 容忍 / Tombstone 不 GC
（叠加既有：客户端崩溃恢复、Server crash 恢复、重复 Pull 幂等、删除/修改冲突、断网退避重试、cursor 重启持久）。

### Deferred Acceptance Test（不阻塞后续阶段）

- GUI-001 ~ GUI-008：双设备 Tauri 真实联调（登录 / A→B Pull / B→A Pull / 删除 / 冲突 / Blob / 离线恢复 / 重启恢复）
  需带显示环境的 Windows 机器一次性手工验收。自动化测试已覆盖同步算法本身，
  本次人工测试目标仅为「真实用户操作链路」。

### 后续（Phase 8/9）

- Phase 8：性能（Lazy Tree/Note/Blob、FTS、10k/100k Notes）
- Phase 9：Windows 打包 / 服务端部署（systemd + Caddy）/ Backup & Restore / 最终验收
