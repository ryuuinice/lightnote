# LightNote v1.1 Release Candidate — Audit Report

**日期**：2026-08-15
**候选基线（HEAD）**：`f8da41e`
**状态**：RC-Ready，**等待 Agent 0 审核后打 `v1.1-rc1` tag**

---

## 1. 基线链路（自上个里程碑 tag 起）

```text
v1.1-phase9.2a-auth
  ↓ 945ab11  chore: repo hygiene + CI（README 凭据清理、gen/schemas 去追踪、.github/workflows/ci.yml）
  ↓ a1ac215  fix: 全量代码评审 P0×6 + P1×8（XSS/DOMPurify/CSP、blob 原子写、路径穿越、FTS 内容搜索、
  ↓          map 竞争、默认凭据、幽灵墓碑、refresh 并发、快捷键、conflicts_resolve…）
  ↓ e293e10  feat: LIGHTNOTE_DATA_DIR（双实例隔离修复）
  ↓ 386cc3e  chore: 忽略服务端运行时产物
  ↓ 37be1f6  feat: 设备管理 API（list + revoke + 级联吊销）
  ↓ ce72edb  fix: devices API snake_case → camelCase
  ↓ 91fbc3a  fix: ureq Status 错误处理（吊销后凭据残留安全缺陷）
  ↓ f8da41e  feat(rc): GUI-003a/009、AUTH-03/07/08、MSI 图标、runbook 回填
HEAD = f8da41e（工作树 clean，已推送 vps/master）
```

## 2. 真机验收结论（Windows 双实例 + VPS 服务端）

14 用例全 PASS（GUI-001~008 + AUTH-01~08）。其中：

- **AUTH-03**：正式验证 —— 服务端小数 TTL（11s）实测，过期后同步无感续期 ✅
- **AUTH-05/08**：吊销 → 踢回登录页 + **credential 物理删除**（缺陷修复后复验）✅
- 详见 `docs/acceptance/gui-acceptance.md`（已回填本次全部教训）

## 3. 本轮 RC 收口项（全部完成）

| # | 项 | 状态 | 验证方式 |
|---|---|---|---|
| 1 | GUI-003a 同步后 UI 自动刷新 | ✅ | 真机：不切换笔记，远端修改立即可见 |
| 2 | GUI-009 本机设备识别 | ✅ | 真机：「（本机）」标记 + 隐藏本机吊销按钮 |
| 3 | AUTH-03 正式验证 | ✅ | 真机：TTL=11s，过期自动 refresh |
| 4 | AUTH-07/08 凭据清除回归 | ✅ | `auth.rs` 文件删除断言 + `refresh_failure_is_fatal` 单测 |
| 5 | MSI bundle icon | ✅ | icon.ico + 全套 Square logos 入库 |
| 6 | runbook 回填 | ✅ | §1.1/§4.1 隔离方案、AUTH-05 设备名、AUTH-07/08 |
| 7 | 全量回归 | ✅ | core 34 / 壳 8 / Go 4 包 / smoke 10 / hardening 8 / slice 9 / Vue build |
| 8 | Windows clean install | ✅ | MSI 安装/快捷方式/图标/功能/卸载/数据保留 |

## 4. 产物

- `LightNote_0.1.0_x64_en-US.msi` + `LightNote_0.1.0_x64-setup.exe`（NSIS）
- VPS 验收服务端：`http://203.0.113.10:28080`（当前 TTL=0.003h 调试值，**发布前需移除或恢复 2h**）

## 5. 遗留（不阻塞 RC，进入 v1.1 final / v1.2 清单）

1. **VPS 环境清理**：probe*/测试设备与测试数据（验收-002 等）需清理；TTL 恢复；HTTPS 未配置
2. P1-3：CONFLICT 后本地高版本变更无对账机制（依赖服务端版本最终超过本地）
3. PERF-001：服务端批量 push 逐条事务（非用户路径，P2）
4. P2 清单：服务端错误日志、refresh token 过期清理、blob 会话 TTL、设置持久化、auto_sync 死开关等（见评审报告）
5. 版本号：`tauri.conf.json`/Cargo 仍为 0.1.0，tag 前需决定 v1.1 版本号策略

## 6. 审核请求

- [ ] Agent 0 确认基线 `f8da41e` 完整性（本报告 §1）
- [ ] 确认后创建 `v1.1-rc1` tag 并推送
- [ ] VPS TTL 恢复决策（建议：rc1 阶段保持独立验收库，不动生产）
