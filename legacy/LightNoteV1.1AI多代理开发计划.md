# LightNote v1.1 AI 多代理开发计划

> **项目：** LightNote  
> **版本：** v1.1  
> **文档状态：** Ready for Development  
> **对应架构文档：** 《LightNote 技术架构设计 v1.1》  
> **开发模式：** AI 多代理并行开发  
> **核心目标：** 本地优先 + 离线可用 + 多设备增量同步 + 最终一致性  
> **开发环境：** Windows + WSL  
> **客户端：** Tauri 2 + Rust + Vue 3 + TypeScript  
> **服务端：** Go + SQLite  
> **部署：** Linux VPS + systemd + Caddy

---

# 1. 项目目标

LightNote v1.1 的目标不是单纯完成一个 Markdown 编辑器，而是建立一个可靠的：

> **Local-First Personal Knowledge Base**

核心架构：

```text
┌──────────────────────────────────────────┐
│              LightNote Client             │
│                                          │
│  Vue 3 UI                                │
│      │                                   │
│      ▼                                   │
│  Tauri 2 / Rust Core                     │
│      ├── Repository                      │
│      ├── Transaction                     │
│      ├── Sync Engine                     │
│      ├── Outbox                          │
│      ├── Blob Manager                    │
│      └── FTS5                            │
│              │                            │
│              ▼                            │
│           SQLite                         │
└──────────────────┬───────────────────────┘
                   │ HTTPS REST
                   ▼
┌──────────────────────────────────────────┐
│              Go Server                   │
│                                          │
│  JWT / Device                            │
│  Sync Push / Commit / Pull               │
│  Conflict                                │
│  Blob Storage                            │
│              │                           │
│              ▼                           │
│          SQLite + Blob                   │
└──────────────────────────────────────────┘
```

---

# 2. 开发核心原则

## 2.1 Local-First

任何核心编辑操作：

```text
用户编辑
   ↓
本地 SQLite
   ↓
立即完成
```

不能依赖服务器。

---

## 2.2 Sync 不阻塞编辑

同步失败不能阻塞：

```text
创建
修改
删除
搜索
打开笔记
```

同步通过：

```text
Change Log
+
Outbox
+
Background Sync
```

异步完成。

---

## 2.3 Blob 不阻塞 Entity Sync

Note 可以先同步：

```text
note
 └── blob_id
```

Blob 后台下载。

---

## 2.4 Pull 不产生 Outbox

远程 Change 应用：

```text
Remote Change
     ↓
Apply Entity
     ↓
is_synced = 1
     ↓
不进入 Outbox
```

防止同步环路。

---

## 2.5 Version Guard

Pull 应用必须检查：

```text
local.version > change.version
```

则：

```text
Skip Change
```

防止未来 Change Log GC 后旧快照回退新数据。

---

## 2.6 API / Schema 优先

任何 Agent 开始编码之前：

```text
Schema
API
Sync Protocol
State Machine
```

必须冻结。

---

# 3. AI 多代理总体架构

推荐：

```text
                         ┌───────────────────────┐
                         │     Agent 0 / Lead     │
                         │                       │
                         │ Architecture          │
                         │ Schema                │
                         │ API Contract          │
                         │ Sync Protocol         │
                         │ Integration            │
                         │ Code Review            │
                         └───────────┬───────────┘
                                     │
                 ┌───────────────────┼───────────────────┐
                 │                   │                   │
                 ▼                   ▼                   ▼
        ┌────────────────┐  ┌────────────────┐  ┌────────────────┐
        │ Agent 1        │  │ Agent 2        │  │ Agent 3        │
        │ Rust Core      │  │ Go Server      │  │ Vue UI         │
        │                │  │                │  │                │
        │ SQLite         │  │ REST           │  │ Editor         │
        │ Repository     │  │ Push/Pull      │  │ Tree           │
        │ Change         │  │ Commit         │  │ Search         │
        │ Outbox         │  │ Conflict       │  │ Settings       │
        └────────────────┘  └────────────────┘  └────────────────┘
                 │                   │                   │
                 └───────────┬───────┴───────────┬───────┘
                             │                   │
                             ▼                   ▼
                    ┌────────────────┐  ┌────────────────┐
                    │ Agent 4        │  │ Agent 5        │
                    │ Blob / FTS     │  │ QA / E2E       │
                    │                │  │                │
                    │ Upload         │  │ Sync Matrix    │
                    │ Download       │  │ Crash Test     │
                    │ Resume         │  │ Integration    │
                    └────────────────┘  └────────────────┘
```

---

# 4. Agent 职责

## Agent 0：Lead / Architect

### 职责

- 架构设计
- Schema 管理
- API Contract
- Sync Protocol
- 状态机
- 任务拆分
- Agent 协调
- Code Review
- Merge
- 集成测试
- 解决跨模块冲突

### 原则

Agent 0 不应该承担大量业务编码。

主要职责：

> **控制系统边界和最终一致性。**

---

# 5. Agent 1：Rust Core

负责：

```text
client/src-tauri/
```

主要模块：

```text
db/
sync/
blob/
search/
api/
```

任务：

- SQLite
- Migration
- Repository
- Transaction
- Change Log
- Outbox
- Cursor
- Sync Engine
- Version Guard
- FTS
- Blob Manager

---

# 6. Agent 2：Go Server

负责：

```text
server/
```

主要模块：

```text
db/
api/
sync/
auth/
blob/
```

任务：

- SQLite
- Migration
- REST API
- Push
- Server Commit
- Pull
- Sequence
- Conflict
- JWT
- Device
- Blob

---

# 7. Agent 3：Vue UI

负责：

```text
client/src/
```

任务：

- App Layout
- Note Tree
- Note List
- Markdown Editor
- Preview
- Search
- Tags
- Trash
- Conflict Center
- Sync Status
- Settings
- Device Management

UI 前期允许使用 Mock Repository。

---

# 8. Agent 4：Blob / FTS

负责：

```text
Blob Storage
FTS
```

如果 Agent 数量不足：

> FTS 可合并给 Agent 1。

主要任务：

- SHA-256 Content Addressing
- Blob Chunk
- Upload
- Resume
- Download
- Lazy Download
- Integrity Check
- Deduplication
- FTS5
- Rebuild

---

# 9. Agent 5：QA / Integration

负责：

```text
tests/
```

任务：

- Unit Test
- Integration Test
- Sync Test
- Two-Client Test
- Crash Recovery
- Network Failure
- Conflict Test
- Blob Test
- E2E
- Performance Test

QA Agent 不应该最后才开始。

---

# 10. Git / Worktree 规范

禁止多个 Agent 共享同一个工作目录进行并行修改。

推荐：

```text
main
 │
 ├── agent/rust-core
 ├── agent/go-server
 ├── agent/vue-ui
 ├── agent/blob
 └── agent/tests
```

如果工具支持 Git Worktree：

```text
worktrees/
├── rust-core/
├── go-server/
├── vue-ui/
├── blob/
└── tests/
```

---

# 11. Agent 提交规范

每个 Agent：

```text
1. 从最新 main 创建任务分支
2. 只修改任务范围内文件
3. 完成单元测试
4. 完成格式检查
5. 提交 commit
6. 提供修改摘要
7. 提供测试结果
8. 请求 Lead Review
```

Commit：

```text
feat(rust): implement note repository
feat(sync): implement push endpoint
feat(ui): add markdown editor
test(sync): add duplicate push test
fix(blob): retry failed chunk
```

---

# 12. Phase 0：架构冻结

> **禁止并行开发业务代码，直到本阶段完成。**

---

## TASK-001：确认仓库结构

负责人：

```text
Agent 0
```

输出：

```text
client/
server/
tests/
docs/
scripts/
```

---

## TASK-002：冻结数据库 Schema

负责人：

```text
Agent 0
```

必须定义：

```text
users
devices
notes
branches
attributes
blobs
entity_changes
sync_outbox
sync_state
```

---

## TASK-003：冻结 Sync Protocol

定义：

```text
Change
Push
Commit
Pull
Cursor
Sequence
Conflict
Version Guard
```

---

## TASK-004：冻结 API

建立：

```text
docs/openapi.yaml
docs/api.md
```

统一：

```text
/api/v1
```

---

## TASK-005：冻结状态机

至少包括：

### Outbox

```text
PENDING
   ↓
SENDING
   ↓
REMOVE

SENDING
   ↓
timeout/crash
   ↓
PENDING
```

### Sync

```text
IDLE
 ↓
PUSHING
 ↓
PULLING
 ↓
APPLYING
 ↓
IDLE
```

---

# 13. Phase 1：基础设施并行开发

Phase 0 完成后：

```text
             ┌──────────────┐
             │ Schema/API   │
             │ Contract     │
             └──────┬───────┘
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
      Rust        Go Server    Vue Mock
        │           │           │
        ▼           ▼           ▼
      Blob/FTS      QA/Test Framework
```

---

# 14. TASK-010～TASK-020：Rust Core

负责人：

```text
Agent 1
```

任务：

```text
TASK-010 SQLite 初始化
TASK-011 Migration
TASK-012 Repository
TASK-013 Transaction
TASK-014 Entity Change
TASK-015 Outbox
TASK-016 Cursor
TASK-017 Sync Engine
TASK-018 Version Guard
TASK-019 FTS
TASK-020 Rust Unit Tests
```

---

# 15. TASK-021～TASK-030：Go Server

负责人：

```text
Agent 2
```

任务：

```text
TASK-021 Go Project
TASK-022 SQLite
TASK-023 Migration
TASK-024 REST Router
TASK-025 Push
TASK-026 Server Commit
TASK-027 Pull
TASK-028 Sequence
TASK-029 Server Tests
TASK-030 Integration Test
```

---

# 16. TASK-031～TASK-040：Vue Mock UI

负责人：

```text
Agent 3
```

任务：

```text
TASK-031 App Layout
TASK-032 Tree
TASK-033 Note List
TASK-034 Editor
TASK-035 Markdown Preview
TASK-036 Search
TASK-037 Tags
TASK-038 Trash
TASK-039 Sync Status
TASK-040 Settings
```

Vue 不等待 Server。

先使用：

```text
MockRepository
```

---

# 17. TASK-041～TASK-050：Blob / FTS

负责人：

```text
Agent 4
```

任务：

```text
TASK-041 SHA256 Content Addressing
TASK-042 Blob Metadata
TASK-043 Chunk Upload
TASK-044 Chunk Resume
TASK-045 Complete
TASK-046 Download
TASK-047 Lazy Download
TASK-048 Integrity Check
TASK-049 Deduplication
TASK-050 Blob Tests
```

---

# 18. TASK-051～TASK-060：测试基础设施

负责人：

```text
Agent 5
```

建立：

```text
tests/
├── integration/
├── sync/
├── crash/
├── blob/
└── e2e/
```

建立：

```text
Test Server
Test Client
Test Database
```

---

# 19. Phase 2：Sync Vertical Slice

> **项目第一核心里程碑。**

目标：

```text
Client A
   │
   │ Push
   ▼
Go Server
   │
   │ Commit
   ▼
Server SQLite
   │
   │ Pull
   ▼
Client B
```

---

# 20. TASK-061：Client Push

Agent：

```text
Agent 1
```

实现：

```text
Outbox
 ↓
HTTP Push
 ↓
Response
 ↓
Remove Outbox
```

---

# 21. TASK-062：Server Push

Agent：

```text
Agent 2
```

实现：

```text
change_id 幂等检查
        ↓
base_version 检查
        ↓
Entity Commit
        ↓
server_sequence
        ↓
COMMIT
```

---

# 22. TASK-063：Client Pull

Agent：

```text
Agent 1
```

实现：

```text
cursor
 ↓
Pull
 ↓
Apply Change
 ↓
Version Guard
 ↓
Update Cursor
```

---

# 23. TASK-064：Server Pull

Agent：

```text
Agent 2
```

实现：

```sql
SELECT *
FROM entity_changes
WHERE server_sequence > ?
ORDER BY server_sequence
LIMIT ?;
```

返回：

```json
{
  "changes": [],
  "next_cursor": 100,
  "has_more": false
}
```

---

# 24. TASK-065：双客户端测试

Agent：

```text
Agent 5
```

验证：

```text
A Create
 ↓
Push
 ↓
Server
 ↓
B Pull
 ↓
B 得到 Note
```

然后：

```text
A Update
B Pull

A Delete
B Pull

B Update
A Pull
```

---

# 25. Sync Vertical Slice Gate

必须全部通过：

```text
□ Create
□ Update
□ Delete
□ Restore
□ Push Idempotency
□ Pull Idempotency
□ Cursor
□ Version
□ Crash Recovery
□ Network Retry
□ Two Clients
```

未通过：

> 禁止进入高级 UI 和认证开发。

---

# 26. Phase 3：Sync Hardening

---

## TASK-070：Version Guard

规则：

```text
if local.version > change.version:
    skip
```

必须测试：

```text
正常 Change
旧 Change
重复 Change
Change Log GC 后旧 Change
```

---

# 27. TASK-071：Conflict

检测：

```text
base_version != current_version
```

策略：

```text
Last Commit Wins
+
Conflict Snapshot
```

冲突副本：

```text
conflict_of_note_id
```

必须产生新的：

```text
entity_change
server_sequence
```

---

# 28. TASK-072：Tombstone

实现：

```text
is_deleted = 1
```

测试：

```text
Delete vs Update
Delete vs Delete
Delete → Restore
Offline Delete
```

v1.1 暂不执行 Tombstone GC。

---

# 29. TASK-073：Cursor Recovery

测试：

```text
Pull 100 Changes
 ↓
Apply 30
 ↓
Crash
 ↓
Restart
 ↓
Retry
```

要求：

> 不丢、不跳、不产生错误最终状态。

---

# 30. TASK-074：Outbox Recovery

测试：

```text
PENDING
 ↓
SENDING
 ↓
kill -9
 ↓
Restart
 ↓
PENDING
 ↓
Retry
```

---

# 31. TASK-075：Server Crash Recovery

测试：

```text
Push
 ↓
kill -9
 ↓
Restart
 ↓
Retry
```

要求：

```text
Entity
Change
Sequence
```

保持一致。

---

# 32. TASK-076：JWT / Device

实现：

```json
{
  "sub": "user-001",
  "device_id": "device-A"
}
```

Server 必须验证：

```text
JWT.device_id == request.device_id
```

否则：

```text
401 / 403
```

---

# 33. Phase 4：Product Parallel Development

同步核心稳定后：

```text
                ┌──────────────┐
                │ Sync Stable  │
                └──────┬───────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
      Vue UI          Blob           Auth
        │              │              │
        └──────────────┼──────────────┘
                       ▼
                 Integration
```

---

# 34. UI 开发

负责人：

```text
Agent 3
```

完成：

```text
Tree
NoteList
Editor
Preview
Search
Tags
Trash
Conflict Center
Sync Status
Settings
Device Management
```

---

# 35. Editor 要求

自动保存：

```text
debounce 500ms～2s
```

流程：

```text
Editor
 ↓
Rust Command
 ↓
SQLite Transaction
 ↓
Change
 ↓
Outbox
```

保存失败不能导致编辑器崩溃。

---

# 36. Tree 要求

必须懒加载：

```text
Root
 ↓
Children
 ↓
Expand
 ↓
Load Children
```

禁止：

```text
启动
 ↓
加载全部 100000 Notes
```

---

# 37. Search

使用：

```text
SQLite FTS5
```

支持：

```text
全文搜索
标题
标签
```

FTS 是本地派生数据：

> 不进入同步协议。

---

# 38. Blob 开发

负责人：

```text
Agent 4
```

Blob ID：

```text
blob_id = SHA256(content)
```

优势：

```text
去重
幂等
完整性校验
断点续传
```

---

# 39. Blob Upload

```text
init
 ↓
chunk 0
 ↓
chunk 1
 ↓
...
 ↓
complete
 ↓
SHA256
 ↓
SUCCESS
```

重复 Chunk：

```text
安全忽略
```

---

# 40. Blob Download

```text
Pull Note
   ↓
发现 blob_id
   ↓
本地不存在
   ↓
Download Queue
   ↓
后台下载
   ↓
SHA256
   ↓
Atomic Rename
```

用户打开笔记时：

> 提升 Blob 下载优先级。

---

# 41. Phase 5：产品完善

完成：

```text
Conflict Center
Device Management
Settings
Trash
Backup
Sync Diagnostics
```

---

# 42. Conflict Center

显示：

```text
Note
当前版本
冲突版本
设备
时间
```

操作：

```text
保留当前
使用冲突版本
手动合并
删除冲突副本
```

---

# 43. Sync Status

状态：

```text
✓ 已同步
↻ 同步中
⚠ 同步失败
○ 离线
```

显示：

```text
最后同步时间
待同步数量
失败数量
```

---

# 44. Phase 6：E2E / 性能 / 发布

---

# 45. E2E 测试矩阵

| 场景 | 必须通过 |
|---|---|
| 创建 Note | ✅ |
| 修改 Note | ✅ |
| 删除 Note | ✅ |
| 恢复 Note | ✅ |
| 重复 Push | ✅ |
| 重复 Pull | ✅ |
| 双设备修改 | ✅ |
| Delete vs Update | ✅ |
| 离线修改 | ✅ |
| 长时间离线 | ✅ |
| Client Crash | ✅ |
| Server Crash | ✅ |
| 网络中断 | ✅ |
| Push Retry | ✅ |
| Pull Retry | ✅ |
| Blob Retry | ✅ |
| Blob 校验失败 | ✅ |
| Token 过期 | ✅ |
| Device Revoke | ✅ |
| Version Guard | ✅ |
| Change GC 模拟 | ✅ |

---

# 46. 性能目标

| 指标 | 目标 |
|---|---:|
| 本地启动 | < 500ms |
| 本地保存 | < 50ms |
| FTS 搜索 | < 200ms |
| Tree 加载 | < 200ms |
| 普通同步 | < 1s |
| 10,000 Notes | 流畅 |
| 100,000 Notes | 可用 |
| 启动内存 | 不随 Note 数量线性增长 |

核心策略：

```text
Tree Lazy Load
Note Lazy Load
Blob Lazy Load
FTS Search
```

---

# 47. Server 部署

```text
Internet
   │
   ▼
Caddy
   │
   │ HTTPS
   ▼
lightnote-server :8080
   │
   ├── SQLite
   │
   └── Blob
```

systemd：

```text
lightnote-server.service
```

---

# 48. 数据目录

```text
/var/lib/lightnote/
├── lightnote.db
├── blobs/
└── backups/
```

---

# 49. Backup / Restore

提供：

```bash
lightnote backup
lightnote restore
```

备份：

```text
SQLite Online Backup
+
Blob Snapshot
```

恢复必须同时保证：

```text
Database
+
Blob
```

一致。

---

# 50. CI

最终 CI 至少包括：

```text
Rust
 ├── cargo fmt --check
 ├── cargo clippy
 └── cargo test

Go
 ├── gofmt
 ├── go vet
 └── go test ./...

Vue
 ├── npm run type-check
 └── npm run build

Integration
 └── Sync E2E
```

---

# 51. 质量门禁

## Gate 1：Schema Freeze

必须：

```text
Schema
API
Sync Protocol
```

冻结。

---

## Gate 2：Local Core

必须：

```text
Entity
Change
Outbox
FTS
```

全部通过测试。

---

## Gate 3：Sync Vertical Slice

必须：

```text
Client A
 ↓
Push
 ↓
Server
 ↓
Pull
 ↓
Client B
```

完整通过。

---

## Gate 4：Sync Stable

必须通过：

```text
Conflict
Version Guard
Tombstone
Retry
Crash Recovery
JWT
Device
```

---

## Gate 5：Product Ready

必须：

```text
UI
Blob
Search
Conflict Center
Settings
```

全部完成。

---

## Gate 6：Release Ready

必须：

```text
E2E
Performance
Backup
Restore
Deployment
Documentation
```

全部通过。

---

# 52. Agent 并行依赖关系

```text
                    Phase 0
                       │
                       ▼
              ┌─────────────────┐
              │ Schema / API    │
              │ Sync Protocol   │
              └────────┬────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
        ▼              ▼              ▼
    Agent 1         Agent 2        Agent 3
    Rust Core       Go Server      Vue Mock
        │              │              │
        │              │              │
        └───────┬──────┘              │
                ▼                     │
        Sync Vertical Slice           │
                │                     │
                ▼                     │
        Sync Hardening                │
                │                     │
        ┌───────┴────────┐            │
        ▼                ▼            │
     Blob/FTS         Auth            │
        │                │            │
        └────────┬───────┘            │
                 │                    │
                 └──────────┬─────────┘
                            ▼
                       Integration
                            │
                            ▼
                           E2E
```

---

# 53. 推荐任务并行表

| Agent | 第一阶段 | 第二阶段 | 第三阶段 |
|---|---|---|---|
| Lead | Schema/API | Review/Integration | Release |
| Rust | SQLite/Core | Sync | Blob/FTS |
| Go | Server Core | Conflict/Auth | Blob |
| Vue | Mock UI | IPC | Product UI |
| Blob | FTS/Blob | Blob Sync | Optimization |
| QA | Test Framework | Sync Matrix | E2E |

---

# 54. 单人 + 多 AI Agent 的推荐模式

如果由一个人负责整个项目，不需要人工逐个编码。

推荐：

```text
你
 │
 ├── Lead Agent
 │
 ├── Rust Agent
 │
 ├── Go Agent
 │
 ├── Vue Agent
 │
 ├── Blob Agent
 │
 └── QA Agent
```

你的主要工作：

```text
1. 确认架构
2. 审核 Agent 输出
3. 处理关键设计决策
4. 合并代码
5. 运行最终 E2E
```

而不是：

```text
自己逐文件写代码
```

---

# 55. 第一轮立即执行任务

当前不要直接让所有 Agent 开始写业务代码。

第一轮：

```text
Agent 0
├── TASK-001 仓库结构
├── TASK-002 Schema
├── TASK-003 Sync Protocol
├── TASK-004 OpenAPI
└── TASK-005 State Machine
```

完成后：

```text
        Schema/API Freeze
               │
       ┌───────┼────────┐
       ▼       ▼        ▼
    Rust      Go       Vue
       │       │        │
       └───┬───┘        │
           ▼            │
       Sync Slice       │
           │            │
           └──────┬─────┘
                  ▼
                QA
```

---

# 56. 第一核心目标

整个项目第一阶段不要把目标定义成：

> “完成 LightNote。”

而应该定义成：

> **“让两个无 UI 的 LightNote 客户端通过 Go Server 完成 Note 的创建、修改、删除、增量同步、幂等重试和崩溃恢复。”**

具体：

```text
Client A
   │
   │ create
   ▼
SQLite
   │
   ▼
Change
   │
   ▼
Outbox
   │
   │ Push
   ▼
Go Server
   │
   ▼
Server Commit
   │
   ▼
server_sequence
   │
   │ Pull
   ▼
Client B
   │
   ▼
Apply Change
   │
   ▼
Version Guard
   │
   ▼
SQLite
```

完成这一闭环之后：

> **LightNote 的最大技术风险已经解决。**

后面的 Vue、Blob、搜索、设备管理等，主要变成工程实现问题。

---

# 57. 最终开发路线

```text
┌─────────────────────────────┐
│ Phase 0                     │
│ Architecture Freeze         │
│ Schema / API / Sync         │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│ Phase 1                     │
│ Parallel Foundation         │
│ Rust / Go / Vue / QA / Blob │
└──────────────┬──────────────┘
               │
               ▼
╔═════════════════════════════╗
║ Sync Vertical Slice ⭐      ║
║                             ║
║ A → Push → Server → Pull → B║
╚══════════════╤══════════════╝
               │
               ▼
┌─────────────────────────────┐
│ Phase 3                     │
│ Sync Hardening              │
│ Conflict / Version / Retry  │
│ Crash / Tombstone / JWT     │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│ Phase 4                     │
│ Product                     │
│ Vue / Editor / Tree / FTS   │
│ Blob / Tags / Trash         │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│ Phase 5                     │
│ Product Complete            │
│ Conflict / Device / Settings│
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│ Phase 6                     │
│ E2E / Performance / Deploy  │
│ Backup / Restore / Release  │
└─────────────────────────────┘
```

---

# 58. Definition of Done

LightNote v1.1 只有同时满足以下条件，才视为完成：

### 数据层

```text
□ SQLite Schema
□ Migration
□ Transaction
□ Repository
□ FTS
```

### 同步层

```text
□ Change Log
□ Outbox
□ Push
□ Server Commit
□ Pull
□ Cursor
□ Idempotency
□ Version Guard
□ Conflict
□ Tombstone
□ Retry
□ Crash Recovery
```

### 产品层

```text
□ Markdown
□ Tree
□ Search
□ Tags
□ Trash
□ Blob
□ Conflict Center
□ Sync Status
□ Settings
□ Device Management
```

### 安全

```text
□ JWT
□ Refresh Token
□ Device Binding
□ Device Revoke
```

### 工程

```text
□ Unit Tests
□ Integration Tests
□ E2E
□ Performance
□ Backup
□ Restore
□ Deployment
□ CI
□ Documentation
```

---

# 59. 开发纪律

最后遵循以下原则：

> **先协议，后代码。**

> **先同步闭环，后产品 UI。**

> **先保证数据正确，再优化性能。**

> **先保证可恢复，再追求高吞吐。**

> **AI Agent 可以并行写代码，但不能并行定义架构。**

> **Schema、API、Sync Protocol 是唯一真相源。**

> **所有 Agent 的代码最终必须经过 Integration Gate。**

---

# 60. 开工指令

第一轮只执行：

```text
TASK-001
TASK-002
TASK-003
TASK-004
TASK-005
```

完成架构冻结后，再同时启动：

```text
Agent 1 → Rust Core
Agent 2 → Go Server
Agent 3 → Vue Mock
Agent 4 → Blob / FTS
Agent 5 → QA Framework
```

第一核心里程碑：

```text
★ SYNC VERTICAL SLICE ★

Client A
    ↓
Push
    ↓
Server Commit
    ↓
Pull
    ↓
Client B
```

**在这个里程碑通过之前，不允许为了“赶进度”跳过同步一致性、幂等、Version Guard 和崩溃恢复测试。**

---

**文档版本：v1.1**  
**状态：Ready for Development**