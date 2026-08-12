# Changelog

本文件记录 LightNote v1.1 重构（Rust + Go + Tauri）的阶段性基线。
架构基线见 `docs/architecture/LightNote技术架构设计v1.1.md`；开发计划见 `docs/architecture/LightNoteV1.2AI多代理开发计划.md`。

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
