# LightNote v1.1-rc1 — Final Release Gate Audit

**日期**：2026-08-15
**候选 HEAD**：`91e5554`
**状态**：Release Gate 全项通过，**等待 Agent 0 最终审核后创建 `v1.1-rc1` tag**

---

## 1. Release Gate 执行结果

| # | Gate 项 | 结果 | 证据 |
|---|---|---|---|
| A | VPS TTL 恢复正式值 | ✅ | 重启后实测 token lifetime = **7200s**（2h，与 `config.example.yaml` 契约一致）；全仓 grep 无 0.003/0.01 调试残留 |
| B | 版本号统一 1.1.0 | ✅ | package.json / tauri.conf.json / 两个 Cargo.toml + lockfiles 均 1.1.0；server 无嵌入版本号（仅 module path）；MSI 产物名 `LightNote_1.1.0_x64_en-US.msi` |
| C | 最终回归 | ✅ | core 34 / 壳 8 / Go 4 包 / smoke 10 / hardening 8+1+7 / vertical-slice 9 / vue-tsc+build / Tauri build |
| D | 安全检查 | ✅ | 见 §2 |
| E | Git 检查 | ✅ | status/diff/cached 全空；0 个 target/node_modules/dist/db/log 被追踪；`v1.1-phase9.2a-auth..HEAD` = 11 提交 |

## 2. 安全检查明细

1. **无调试 TTL 残留**：源码/配置/CI grep 0.003/0.01 → 无（runbook 中的 0.003 是技术文档说明，非配置）
2. **refresh_token 存储边界**：仅存于 Rust 侧 `FileCredentialStore`（0600 文件）；前端命中均为注释；不入 SQLite、不过 webview、无 localStorage/sessionStorage
3. **凭据清除**：logout/revoke → `clear_session`（6 处调用）+ `clear_refresh`（4 处）；AUTH-07/08 回归测试锚点在库
4. **产物无测试数据**：git 追踪文件无 .db/.log/credential/probe 数据

## 3. Windows 安装包验证（v1.1.0）

| 检查项 | 结果 |
|---|---|
| MSI 构建 | ✅ `LightNote_1.1.0_x64_en-US.msi`（注：perMachine 静默安装需管理员权限，Error 1925 属权限而非包缺陷） |
| NSIS 构建+安装 | ✅ `LightNote_1.1.0_x64-setup.exe` 静默安装 exit 0，注册表 DisplayVersion=1.1.0 |
| 开始菜单快捷方式 | ✅ |
| 启动 | ✅ 进程正常运行 |
| 静默卸载 | ✅ 安装目录移除，**AppData 用户数据保留**（符合产品策略） |

## 4. 真机验收总览（本日）

- **14/14 用例 PASS**（GUI-001~008、AUTH-01~08，AUTH-03 为正式验证：TTL=11s 实测无感续期）
- 真机发现并修复 4 缺陷：双实例隔离（LIGHTNOTE_DATA_DIR）、设备 API 缺失、字段大小写、ureq Status 凭据残留（安全）
- RC 收口 8 项全过（GUI-003a/009、AUTH-03/07/08、MSI 图标、runbook、回归、clean install）

## 5. 性能基线（沿用 Phase 8/9.2，本日未回归）

- 100K 笔记首同步 9.5s（10521 chg/s）、内存平稳（server 31MB / client 12MB）
- FTS 100K Gate、Push 709 chg/s（PERF-001 Deferred）

## 6. 已知非阻塞问题（进入 v1.1 final / v1.2 backlog）

1. VPS 验收库含测试数据与 probe 设备（rc1 不清理，final 前清理）
2. P1-3 CONFLICT 后本地高版本无对账机制
3. PERF-001 服务端批量 push 逐条事务
4. P2 清单（服务端错误日志、refresh token 清理、blob 会话 TTL、设置持久化、auto_sync 死开关等）
5. 小瑕疵：NSIS 安装的执行文件名为 `lightnote_app.exe`（与产品名 LightNote 不一致，非阻塞）

## 7. 冻结声明（RC 阶段策略）

- 只允许修：P0 安全 / P0 数据一致性 / P0 崩溃 / 阻塞发布的 Windows 问题
- 不允许：新功能、架构重构、同步协议变化、Schema 变化、PERF-001
- v1.1 Final 判据：RC 真机短期使用无新 P0/P1

## 8. 审核请求

- [ ] Agent 0 确认本报告 → `git tag v1.1-rc1 && git push vps v1.1-rc1`
- [ ] 冻结 master 为 RC 分支线
