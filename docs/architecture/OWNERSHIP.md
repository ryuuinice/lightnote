# LightNote Agent 文件所有权登记

> **状态：** Contract Freeze  
> **所有者：** Agent 0  
> **依据：** 《LightNote v1.2 AI 多代理开发计划》§6-7  
> **规则：** 每个文件只有一个 Agent Owner；非 Owner 修改必须经 Owner 或 Agent 0 协调；同一文件并发修改必须串行

---

# 1. 所有权矩阵

| 路径 | Owner | 说明 |
|---|---|---|
| `docs/**` | Agent 0 | 全部契约文档（schema/openapi/ipc/change-protocol/架构/计划） |
| `client/src-tauri/**` | Agent 1 | Rust Core |
| `client/src/**` | Agent 3 | Vue UI |
| `client/src-tauri/blob/**` | Agent 1 | Blob Manager（Agent 4 协作，最终由 Agent 1 Review 合入） |
| `server/**` | Agent 2 | Go Server |
| `server/internal/api/blobs*` | Agent 2 | Server Blob Handler（Agent 4 协作，最终由 Agent 2 Review 合入） |
| `tests/**` | Agent 5 | QA / Integration |
| `scripts/**` | Agent 0 | 构建/部署脚本 |
| `client/README.md` | Agent 0 | 目录说明 |
| `server/README.md` | Agent 0 | 目录说明 |
| `tests/README.md` | Agent 0 | 目录说明 |
| `README.md` | Agent 0 | 项目总入口（Phase 收尾重写） |
| `legacy/**` | Agent 0 | 旧代码归档，冻结不动 |
| `.gitignore` | Agent 0 | 共享配置 |
| `.github/**` | Agent 0 | CI（Phase 8 启用） |

# 2. 契约文件（仅 Agent 0 可修改）

```text
docs/schema/common.sql
docs/schema/client.sql
docs/schema/server.sql
docs/openapi.yaml
docs/api.md
docs/ipc.md
docs/change-protocol.md
docs/architecture/LightNote技术架构设计v1.1.md
docs/architecture/LightNoteV1.2AI多代理开发计划.md
```

# 3. 协作边界（Blob 跨端）

```text
Blob Protocol 设计   → Agent 4 起草 → Agent 0 冻结
客户端 Blob Manager   → Agent 4 实现 → Agent 1 Review 后合入 client/src-tauri/blob/
服务端 Blob Handler   → Agent 4 实现 → Agent 2 Review 后合入 server/
Blob 契约变更         → Agent 4 提变更说明 → Agent 0 批准
```

# 4. 冲突处理

```text
两个 Agent 需要改同一文件 → 立即停止 → 通知 Agent 0 → 串行排期
发现所有权冲突         → 立即停止 → 通知 Agent 0
契约与代码不一致        → 契约优先 → Agent 0 裁定
```
