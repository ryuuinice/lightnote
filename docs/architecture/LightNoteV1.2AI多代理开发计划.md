# LightNote v1.2 AI 多代理开发计划

> **项目：** LightNote  
> **开发计划版本：** v1.2  
> **架构基线：** 《LightNote 技术架构设计 v1.1》  
> **状态：** Ready for Development  
> **开发模式：** OpenCode AI 多代理并行开发  
> **客户端：** Tauri 2 + Rust + Vue 3 + TypeScript  
> **服务端：** Go + SQLite  
> **部署：** Linux VPS + systemd + Caddy  
> **开发环境：** Windows + WSL

---

# 1. 开发目标

LightNote v1.1 的核心目标是构建一个：

> **Local-First + Offline-First + Incremental Sync + Multi-Device + Eventually Consistent**

的个人知识库。

核心要求：

1. 本地编辑不依赖网络。
2. 本地保存必须快速完成。
3. 同步在后台异步执行。
4. 支持多设备。
5. 支持离线长时间使用。
6. 支持增量同步。
7. 支持冲突检测与冲突副本。
8. 支持 Blob 内容寻址、去重和断点续传。
9. 客户端与服务端最终一致。
10. AI Agent 可以并行开发，但所有共享契约必须由 Agent 0 统一管理。

---

# 2. 架构基线

本开发计划基于：

> **《LightNote 技术架构设计 v1.1》**

开发计划 v1.2 **不重新定义架构**。

必须遵守：

```text
架构设计 v1.1
       │
       ▼
开发计划 v1.2
       │
       ▼
代码实现
```

原则：

> 架构设计文档是架构真相源，开发计划不得自行增加数据库字段、同步状态、协议语义或认证机制。

如果开发过程中发现架构需要修改：

```text
发现问题
   ↓
修改架构文档
   ↓
Agent 0 Review
   ↓
更新 Contract
   ↓
通知所有 Agent
   ↓
继续开发
```

---

# 3. 总体架构

```text
┌────────────────────────────────────────────┐
│                LightNote Client            │
│                                            │
│  Vue 3 + TypeScript                        │
│          │                                 │
│          │ Tauri IPC                       │
│          ▼                                 │
│  Tauri 2 / Rust                            │
│  ├── Repository                            │
│  ├── Transaction                           │
│  ├── Change Log                            │
│  ├── Outbox                                │
│  ├── Sync Engine                           │
│  ├── Blob Manager                          │
│  └── FTS5                                  │
│          │                                 │
│          ▼                                 │
│       SQLite                               │
└────────────────┬───────────────────────────┘
                 │
                 │ HTTPS REST /api/v1
                 │ JWT
                 ▼
┌────────────────────────────────────────────┐
│                Go Server                   │
│                                            │
│  HTTP API                                  │
│  ├── Auth                                  │
│  ├── Sync                                  │
│  ├── Notes                                 │
│  ├── Branches                              │
│  ├── Attributes                            │
│  ├── Blobs                                 │
│  └── Devices                               │
│                                            │
│  SQLite + Blob Storage                     │
└────────────────────────────────────────────┘
```

---

# 4. 核心开发原则

## 4.1 Local-First

编辑流程：

```text
用户编辑
   ↓
Tauri IPC
   ↓
Rust Transaction
   ↓
SQLite
   ↓
Change + Outbox
   ↓
立即返回 UI
```

网络不参与本地保存成功与否的判断。

---

## 4.2 Editor Save 与 Sync 解耦

必须明确区分：

### 本地保存

```text
Editor
 ↓
500ms ~ 2s debounce
 ↓
SQLite Transaction
```

### 同步

```text
Change Log
 ↓
Outbox
 ↓
Sync Scheduler
 ↓
Push
```

默认同步周期：

```text
60s
```

同时支持：

```text
手动立即同步
应用启动同步
网络恢复同步
```

两者完全解耦。

---

## 4.3 Change Log 与 Outbox 职责分离

这是 v1.1 同步架构的重要原则。

```text
entity_changes
    │
    └── 记录变更历史 / 同步事实

sync_outbox
    │
    └── 记录本地待发送任务
```

二者不是同一个概念。

Pull 应用远端 Change 时：

```text
Remote Change
     ↓
Apply Entity
     ↓
记录/保留 Change History
     ↓
更新 Cursor
```

**不得因此产生新的 `sync_outbox` 待发送记录。**

特别注意：

> v1.1 不采用 `is_synced` 字段控制同步环路。

禁止重新引入：

```text
is_synced
```

作为同步机制。

---

## 4.4 Pull 不产生 Outbox

远程 Change：

```text
Pull
 ↓
Apply Change
 ↓
Entity Updated
 ↓
❌ 不创建 sync_outbox
```

同步环路通过：

```text
Change 来源
+
Outbox 职责分离
+
origin_device_id
+
change_id 幂等
```

解决。

---

## 4.5 Version Guard

Pull 应用 Change 前：

```text
if local.version > change.version:
    skip
```

用于防止：

> Change Log GC 后旧快照导致新数据回退。

因此：

```text
change_id
```

负责：

> 去重。

而：

```text
version
```

负责：

> 数据新旧保护。

两者职责不同。

---

## 4.6 Blob 与 Entity Sync 解耦

Note Change：

```text
Note
 └── blob_id
```

Blob：

```text
后台异步下载
```

打开笔记时如果 Blob 不存在：

```text
提高 Blob Download Queue 优先级
```

---

# 5. AI Agent 总体角色

```text
                    ┌──────────────────────┐
                    │ Agent 0 / Lead       │
                    │ Architecture         │
                    │ Contract             │
                    │ Integration          │
                    └──────────┬───────────┘
                               │
       ┌───────────────────────┼───────────────────────┐
       │                       │                       │
       ▼                       ▼                       ▼
┌──────────────┐       ┌──────────────┐       ┌──────────────┐
│ Agent 1      │       │ Agent 2      │       │ Agent 3      │
│ Rust Core    │       │ Go Server    │       │ Vue UI       │
│              │       │              │       │              │
│ SQLite       │       │ REST         │       │ Editor       │
│ Repository   │       │ Sync         │       │ Tree         │
│ Change       │       │ Auth         │       │ Search       │
│ Outbox       │       │ Conflict     │       │ Settings     │
│ FTS          │       │ Blob API     │       │              │
└──────────────┘       └──────────────┘       └──────────────┘
       │                       │                       │
       └───────────────┬───────┴───────────────┬───────┘
                       │                       │
                       ▼                       ▼
               ┌──────────────┐       ┌──────────────┐
               │ Agent 4      │       │ Agent 5      │
               │ Blob         │       │ QA / E2E     │
               │ Specialist   │       │              │
               │              │       │ Sync Matrix  │
               │ Protocol     │       │ Crash        │
               │ Chunk        │       │ Conflict     │
               │ Resume       │       │ Performance   │
               └──────────────┘       └──────────────┘
```

---

# 6. Agent 职责与文件所有权

OpenCode 子代理共享同一文件系统。

因此本项目：

> **不依赖 Git Worktree 实现 Agent 隔离。**

采用：

> **目录隔离 + 文件所有权 + 重叠文件串行修改**

作为并行开发机制。

---

## 6.1 Agent 0：Lead

主要负责：

```text
docs/
scripts/
integration/
```

负责：

- Architecture
- Schema Contract
- OpenAPI
- IPC Contract
- Change Protocol
- State Machine
- 任务拆分
- Agent 调度
- Code Review
- Integration
- Release

公共 Contract 文件默认只允许 Agent 0 修改。

---

## 6.2 Agent 1：Rust Core

主要所有权：

```text
client/src-tauri/db/
client/src-tauri/sync/
client/src-tauri/search/
client/src-tauri/api/
client/src-tauri/commands.rs
```

负责：

- SQLite
- Migration
- Repository
- Transaction
- Change
- Outbox
- Cursor
- Sync Engine
- Version Guard
- FTS5
- Tauri Commands

---

## 6.3 Agent 2：Go Server

主要所有权：

```text
server/
```

负责：

- HTTP
- JWT
- Auth
- SQLite
- Sync
- Conflict
- Notes
- Branches
- Attributes
- Server Blob API
- Device API

---

## 6.4 Agent 3：Vue UI

主要所有权：

```text
client/src/
```

负责：

- Layout
- Tree
- Editor
- Preview
- Search UI
- Tags
- Trash
- Conflict Center
- Sync Status
- Settings
- Device Management

UI 必须严格按照：

```text
docs/ipc.md
```

调用 Tauri。

---

## 6.5 Agent 4：Blob Specialist

负责：

- Blob Protocol
- Chunk Upload
- Chunk Download
- Resume
- SHA-256
- Deduplication
- Blob Download Queue

注意：

> Agent 4 不拥有整个 Rust 或 Go 目录。

客户端 Blob 最终接入由 Agent 1 Review。

服务端 Blob Handler 最终接入由 Agent 2 Review。

如果 Agent 4 与 Agent 1/2 需要修改同一文件：

> 必须串行执行。

---

## 6.6 Agent 5：QA

主要所有权：

```text
tests/
```

负责：

- Unit Test
- Integration Test
- Sync Test
- Conflict Test
- Crash Test
- Retry Test
- Blob Test
- E2E
- Performance

---

# 7. Agent 文件所有权规则

由于 OpenCode 子代理共享文件系统，必须遵守：

1. 每个文件默认只有一个 Agent Owner。
2. Owner Agent 可以独立修改自己负责的文件。
3. 非 Owner Agent 不得直接修改该文件。
4. 跨 Agent 修改必须由 Owner Agent 执行，或由 Agent 0 协调。
5. 公共 Contract 文件只能由 Agent 0 修改。
6. 同一文件存在多个修改需求时，任务必须串行执行。
7. Agent 完成任务后必须先通过本模块测试，再通知 Agent 0 集成。
8. 不允许两个 Agent 同时对同一个文件进行编辑。
9. Agent 不得擅自修改其他 Agent 的目录。
10. 如果发现目录或文件所有权冲突，立即停止该文件修改并通知 Agent 0。

---

# 8. 仓库结构

新架构不直接覆盖旧 Java/Spring Boot 项目。

推荐：

```text
lightnote/
├── client/
│   ├── src/
│   └── src-tauri/
│
├── server/
│
├── tests/
│
├── docs/
│   ├── architecture/
│   ├── schema/
│   │   ├── common.sql
│   │   ├── client.sql
│   │   └── server.sql
│   ├── api.md
│   ├── openapi.yaml
│   ├── ipc.md
│   └── change-protocol.md
│
├── scripts/
│
└── legacy/
    ├── lightnote-client/
    └── lightnote-server/
```

旧 Java/Spring Boot 代码先移动到：

```text
legacy/
```

验证新版本稳定后再删除。

---

# 9. Phase 0：Architecture / Contract Freeze

> **正式并行开发前的强制阶段。**

只有本阶段完成，才允许 Agent 1～5 大规模并行。

---

# 10. TASK-001：仓库迁移

负责人：

```text
Agent 0
```

完成：

```text
旧 Java Client
旧 Spring Server
        ↓
legacy/
```

新建：

```text
client/
server/
```

验收：

```bash
git status
```

确认：

> 新旧代码没有互相覆盖。

---

# 11. TASK-002：Schema Single Source of Truth

负责人：

```text
Agent 0
```

Schema 不采用：

```text
Rust schema.rs
Go schema.sql
```

双写模式。

统一：

```text
docs/schema/
├── common.sql
├── client.sql
└── server.sql
```

---

## 11.1 common.sql

以架构设计 v1.1 为准定义公共实体：

```text
users
notes
branches
attributes
blobs
entity_changes
```

---

## 11.2 client.sql

定义客户端专有结构：

```text
sync_outbox
sync_state
blob_download_queue
FTS5
客户端索引
```

---

## 11.3 server.sql

定义服务端专有结构：

```text
devices
device_sync_state
服务端索引
服务端同步状态
```

---

## 11.4 Schema 关键原则

开发计划不得自行增加架构文档不存在的字段。

例如：

> 不得因为开发便利重新引入 `is_synced`。

`entity_changes` 必须以架构 v1.1 最终定义为准。

如果架构定义使用：

```text
origin_device_id
```

则不得额外增加：

```text
instance_id
```

除非先修改架构文档并经过 Agent 0 确认。

---

# 12. Schema 执行原则

`docs/schema/*.sql` 是：

> **数据库结构唯一真相源。**

Rust：

```text
SQL
 ↓
embedded / migration
 ↓
SQLite
```

Go：

```text
SQL
 ↓
embed
 ↓
SQLite
```

代码可以有：

```text
Repository
Model
Mapping
```

但不得成为第二套 Schema 来源。

---

# 13. TASK-003：Sync Protocol Freeze

负责人：

```text
Agent 0
```

文件：

```text
docs/change-protocol.md
```

必须定义：

```text
Change
Push
Server Commit
Pull
Cursor
Sequence
Conflict
Version Guard
Idempotency
Origin Device
```

---

# 14. Change Payload

统一格式：

```json
{
  "change_id": "01J...",
  "entity_type": "note",
  "entity_id": "01J...",
  "operation": "UPDATE",
  "base_version": 3,
  "version": 4,
  "origin_device_id": "device-a",
  "payload": {
    "title": "Example",
    "type": "markdown",
    "blob_id": "sha256:..."
  }
}
```

原则：

### Entity

Change 中携带完整实体快照。

### Blob

Change 只携带：

```text
blob_id
```

正文/附件通过 Blob API 单独传输。

---

# 15. TASK-004：OpenAPI Freeze

负责人：

```text
Agent 0
```

文件：

```text
docs/openapi.yaml
docs/api.md
```

API：

```text
/api/v1/auth/*
/api/v1/notes/*
/api/v1/branches/*
/api/v1/attributes/*
/api/v1/sync/*
/api/v1/blobs/*
/api/v1/devices/*
```

---

# 16. TASK-005：Tauri IPC Freeze

负责人：

```text
Agent 0
```

文件：

```text
docs/ipc.md
```

至少定义：

```text
notes.list
notes.get
notes.create
notes.update
notes.delete

tree.children

search.query

sync.status
sync.trigger

settings.get
settings.update

devices.list
devices.revoke
```

Vue Mock 必须遵守完全相同的接口签名。

---

# 17. TASK-006：State Machine Freeze

## 17.1 Outbox

```text
PENDING
   │
   ▼
SENDING
   │
   ▼
SUCCESS
   │
   ▼
REMOVE

SENDING
   │
   │ timeout / crash
   ▼
PENDING
```

---

## 17.2 Sync

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

# 18. TASK-007：最小 JWT Contract

> 最小 JWT 必须在 Sync Vertical Slice 之前完成。

Access Token：

```json
{
  "sub": "user-001",
  "device_id": "device-A"
}
```

Server：

```text
JWT.device_id
      ↓
origin_device_id
```

禁止：

```text
Request.device_id
      ↓
覆盖 JWT 身份
```

Refresh Token、设备吊销等完整认证能力后置。

---

# 19. Phase 0 Gate：Contract Freeze

必须全部完成：

```text
□ Repository Structure
□ Schema
□ API
□ IPC
□ Change Payload
□ State Machine
□ Minimal JWT
```

完成后：

> **Contract Freeze**

之后任何 Contract 修改必须经过 Agent 0。

---

# 20. Phase 1：并行基础开发

Contract Freeze 后同时启动：

```text
Agent 1 → Rust Core
Agent 2 → Go Server
Agent 3 → Vue Mock
Agent 4 → Blob
Agent 5 → QA
```

---

# 21. Agent 1：Rust Core Tasks

```text
TASK-010 SQLite Initialization
TASK-011 Migration
TASK-012 Repository
TASK-013 Transaction
TASK-014 Change Log
TASK-015 Outbox
TASK-016 Cursor
TASK-017 Sync Engine
TASK-018 Version Guard
TASK-019 FTS5
TASK-020 Tauri Commands
TASK-021 Rust Unit Tests
```

---

# 22. Agent 2：Go Server Tasks

```text
TASK-030 Go Project
TASK-031 SQLite
TASK-032 Migration
TASK-033 HTTP Router
TASK-034 JWT Middleware
TASK-035 Minimal Auth
TASK-036 Push
TASK-037 Server Commit
TASK-038 Pull
TASK-039 Sequence
TASK-040 Notes API
TASK-041 Branch API
TASK-042 Attribute API
TASK-043 Server Unit Tests
```

---

# 23. Agent 3：Vue Tasks

可以与 Rust/Go 并行。

```text
TASK-050 Layout
TASK-051 Tree
TASK-052 Note List
TASK-053 Editor
TASK-054 Markdown Preview
TASK-055 Search UI
TASK-056 Tags
TASK-057 Trash
TASK-058 Sync Status
TASK-059 Settings
TASK-060 Mock Repository
```

UI 使用：

```text
Mock IPC
```

开发。

---

# 24. Agent 4：Blob Tasks

```text
TASK-070 Blob ID / SHA256
TASK-071 Blob Metadata
TASK-072 Chunk Upload
TASK-073 Chunk Resume
TASK-074 Complete
TASK-075 Download
TASK-076 Download Queue
TASK-077 Integrity Check
TASK-078 Deduplication
TASK-079 Blob Tests
```

---

# 25. Agent 5：QA Tasks

```text
TASK-080 Test Framework
TASK-081 Test Server
TASK-082 Test Client
TASK-083 Sync Fixtures
TASK-084 HTTP Failure Injection
TASK-085 Crash Injection
TASK-086 Two Client Harness
```

---

# 26. Phase 2：Sync Vertical Slice

> **LightNote v1.1 第一核心里程碑。**

目标：

```text
Client A
   │
   │ Push
   ▼
Go Server
   │
   │ Server Commit
   ▼
Server SQLite
   │
   │ Pull
   ▼
Client B
```

---

# 27. TASK-090：Client Push

```text
Outbox
 ↓
HTTP
 ↓
Push
 ↓
Response
 ↓
Outbox Remove
```

要求：

```text
change_id
base_version
origin_device_id
```

全部符合 Contract。

---

# 28. TASK-091：Server Commit

事务：

```text
BEGIN
 ↓
Idempotency Check
 ↓
Load Entity
 ↓
Check base_version
 ↓
Apply Change
 ↓
Create entity_change
 ↓
Allocate server_sequence
 ↓
COMMIT
```

---

# 29. TASK-092：Server Pull

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

# 30. TASK-093：Client Pull

```text
cursor
 ↓
Pull
 ↓
Apply Change
 ↓
Version Guard
 ↓
Commit Entity + Cursor
```

---

# 31. TASK-094：Sync Loop Prevention

Pull：

```text
Remote Change
 ↓
Apply
 ↓
更新 Entity / Change History
 ↓
❌ 不创建 sync_outbox
```

禁止：

```text
is_synced
```

作为同步环路控制机制。

---

# 32. TASK-095：Two Client Test

```text
A Create
 ↓
Push
 ↓
Server
 ↓
B Pull
```

然后：

```text
A Update
B Pull

B Update
A Pull

A Delete
B Pull
```

---

# 33. Sync Vertical Slice Gate

必须全部通过：

```text
□ Create
□ Update
□ Delete
□ Push
□ Pull
□ Idempotency
□ Cursor
□ Version Guard
□ Loop Prevention
□ JWT
□ Two Clients
```

未通过：

> 禁止进入完整产品集成。

---

# 34. Phase 3：Sync Hardening

---

## TASK-100：Conflict

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

冲突副本必须包含：

```text
conflict_of_note_id
```

并产生新的：

```text
entity_change
server_sequence
```

---

## TASK-101：Delete vs Update

测试：

```text
A Delete
B Update
```

要求：

> 不允许出现数据静默丢失。

---

## TASK-102：Version Guard

测试：

```text
正常 Change
旧 Change
重复 Change
Change Log GC 后旧 Change
```

规则：

```text
local.version > change.version
        ↓
      Skip
```

---

## TASK-103：Cursor Recovery

```text
Pull 100
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

```text
不丢
不跳
不产生错误最终状态
```

---

## TASK-104：Outbox Recovery

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

## TASK-105：Server Crash

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

一致。

---

## TASK-106：Retry / Backoff

采用指数退避：

```text
1s
2s
4s
8s
16s
...
```

支持最大重试限制。

---

# 35. Phase 4：Blob Integration

Blob 不作为独立孤立系统存在，而是接入 Sync。

---

## Blob Upload

```text
Note Save
 ↓
Blob Manager
 ↓
SHA256
 ↓
init
 ↓
chunks
 ↓
complete
 ↓
server verify
```

---

## Blob Download

```text
Pull Change
 ↓
发现 blob_id
 ↓
本地不存在
 ↓
blob_download_queue
 ↓
后台下载
 ↓
SHA256
 ↓
Atomic Rename
```

用户打开笔记：

```text
提高 priority
```

---

## Blob Failure

```text
retry
 ↓
retry
 ↓
FAILED
```

不得阻塞：

```text
Note
Editor
Tree
Search
```

---

# 36. Phase 5：完整 UI 集成

Agent 3 将：

```text
Mock IPC
```

替换为：

```text
Real Tauri IPC
```

---

# 37. UI 功能

```text
□ Note Tree
□ Note List
□ Markdown Editor
□ Markdown Preview
□ Search
□ Tags
□ Trash
□ Conflict Center
□ Sync Status
□ Settings
□ Device Management
```

---

# 38. Editor

本地保存：

```text
500ms ~ 2s debounce
```

流程：

```text
Editor
 ↓
Tauri IPC
 ↓
Rust
 ↓
SQLite Transaction
 ↓
Change
 ↓
Outbox
```

Push 不由 Editor 直接触发。

---

# 39. Tree

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
一次加载全部 Notes
```

---

# 40. FTS

FTS5：

```text
本地维护
```

不进入同步协议。

Change Apply 时：

```text
Entity Update
+
FTS Update
```

在同一事务内完成。

---

# 41. Phase 6：Auth / Device 完整功能

最小 JWT 已经在 Vertical Slice 前完成。

此阶段补充：

```text
Refresh Token
Device List
Device Revoke
Token Expiration
Device Last Seen
```

---

# 42. Device Revoke

服务端：

```text
Device
 ↓
revoked_at
```

之后：

```text
Access Token
 ↓
拒绝
```

---

# 43. Phase 7：E2E

## 双设备

```text
A Create
B Pull

A Update
B Pull

B Update
A Pull
```

## 冲突

```text
A Update
B Update
       ↓
Conflict
       ↓
Conflict Snapshot
```

## 删除冲突

```text
A Delete
B Update
```

## 离线

```text
A Offline
 ↓
大量修改
 ↓
Reconnect
 ↓
Push
 ↓
Pull
```

## 网络故障

```text
Push
 ↓
Connection Reset
 ↓
Retry
```

## Server Crash

```text
Push
 ↓
kill -9
 ↓
Restart
 ↓
Retry
```

---

# 44. Phase 8：性能测试

目标：

| 指标 | 目标 |
|---|---:|
| 本地启动 | < 500ms |
| 本地保存 | < 50ms |
| FTS 搜索 | < 200ms |
| Tree 加载 | < 200ms |
| 普通同步 | < 1s |
| 10,000 Notes | 流畅 |
| 100,000 Notes | 可用 |

核心策略：

```text
Lazy Tree
Lazy Note
Lazy Blob
FTS
```

---

# 45. Phase 9：部署

架构：

```text
Internet
   │
   ▼
Caddy
   │ HTTPS
   ▼
lightnote-server :8080
   │
   ├── lightnote.db
   └── blobs/
```

systemd：

```text
lightnote-server.service
```

---

# 46. Server 数据目录

```text
/var/lib/lightnote/
├── lightnote.db
├── blobs/
└── backups/
```

---

# 47. Backup

使用：

```text
SQLite Online Backup
+
Blob Snapshot
```

必须提供：

```bash
lightnote backup
lightnote restore
```

---

# 48. CI

Rust：

```bash
cargo fmt --check
cargo clippy
cargo test
```

Go：

```bash
gofmt
go vet ./...
go test ./...
```

Vue：

```bash
npm run type-check
npm run build
```

E2E：

```text
Sync Integration
Two Client
Conflict
Crash
Blob
```

---

# 49. Quality Gates

## Gate 0：Contract Freeze

```text
□ Schema
□ API
□ IPC
□ Change Payload
□ State Machine
□ Minimal JWT
```

---

## Gate 1：Local Core

```text
□ SQLite
□ Repository
□ Transaction
□ Change
□ Outbox
□ FTS
```

---

## Gate 2：Sync Vertical Slice

```text
□ Push
□ Commit
□ Pull
□ Cursor
□ Idempotency
□ Version Guard
□ JWT
□ Two Clients
```

---

## Gate 3：Sync Stable

```text
□ Conflict
□ Tombstone
□ Retry
□ Crash Recovery
□ Device
```

---

## Gate 4：Product

```text
□ UI
□ Blob
□ Search
□ Conflict Center
□ Settings
```

---

## Gate 5：Release

```text
□ E2E
□ Performance
□ Backup
□ Restore
□ Deployment
□ CI
□ Documentation
```

---

# 50. 任务依赖

```text
                    ┌──────────────────┐
                    │ Phase 0          │
                    │ Contract Freeze  │
                    └────────┬─────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
           Rust Core      Go Server       Vue Mock
              │              │              │
              │              │              │
              └───────┬──────┘              │
                      ▼                     │
                Sync Vertical               │
                      │                     │
                      ▼                     │
                Sync Hardening              │
                      │                     │
            ┌─────────┼──────────┐          │
            ▼         ▼          ▼          │
          Blob      Auth       Conflict     │
            │         │          │          │
            └─────────┼──────────┘          │
                      │                     │
                      └──────────┬──────────┘
                                 ▼
                              UI Integration
                                 │
                                 ▼
                                E2E
                                 │
                                 ▼
                               Release
```

---

# 51. AI Agent 并行策略

## 第一轮：Contract

只允许 Agent 0：

```text
TASK-001
TASK-002
TASK-003
TASK-004
TASK-005
TASK-006
TASK-007
```

完成：

```text
Contract Freeze
```

---

## 第二轮：Parallel Foundation

Contract Freeze 后：

```text
Agent 1 → Rust Core
Agent 2 → Go Server
Agent 3 → Vue Mock
Agent 4 → Blob
Agent 5 → QA
```

并行开发。

---

## 第三轮：Sync Integration

Rust + Go 完成最小同步闭环：

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

---

# 52. Contract 变更规则

任何 Agent 修改：

```text
docs/schema/
docs/openapi.yaml
docs/ipc.md
docs/change-protocol.md
```

必须：

```text
提交变更说明
 ↓
Agent 0 Review
 ↓
修改版本
 ↓
通知所有相关 Agent
```

不得：

> 私自修改公共 Contract 后继续实现。

---

# 53. Definition of Done

## Data

```text
□ SQLite
□ Migration
□ Repository
□ Transaction
□ FTS
```

## Sync

```text
□ Change Log
□ Outbox
□ Push
□ Commit
□ Pull
□ Cursor
□ Idempotency
□ Version Guard
□ Conflict
□ Tombstone
□ Retry
□ Crash Recovery
```

## Blob

```text
□ SHA256
□ Dedup
□ Chunk
□ Resume
□ Upload
□ Download
□ Lazy Download
□ Integrity Check
```

## Product

```text
□ Markdown
□ Tree
□ Search
□ Tags
□ Trash
□ Conflict Center
□ Sync Status
□ Settings
□ Devices
```

## Security

```text
□ JWT
□ user_id
□ device_id
□ Refresh Token
□ Device Revoke
```

## Engineering

```text
□ Unit Test
□ Integration Test
□ E2E
□ Performance
□ Backup
□ Restore
□ CI
□ Documentation
```

---

# 54. 第一核心里程碑

LightNote v1.1 第一阶段**不以 UI 完成为目标**。

真正的第一目标是：

```text
┌──────────────┐
│ Client A     │
│              │
│ Create Note  │
└──────┬───────┘
       │
       ▼
   SQLite
       │
       ▼
 Change + Outbox
       │
       │ Push
       ▼
┌──────────────┐
│ Go Server    │
│              │
│ Commit       │
│ Sequence     │
└──────┬───────┘
       │
       │ Pull
       ▼
┌──────────────┐
│ Client B     │
│              │
│ Apply        │
│ VersionGuard │
└──────────────┘
```

必须支持：

```text
Create
Update
Delete
Push
Pull
Retry
Idempotency
Version Guard
JWT
Two Clients
Crash Recovery
```

---

# 55. 第一轮立即开工任务

正式开工只执行：

```text
TASK-001  仓库迁移
TASK-002  Schema Single Source
TASK-003  Sync Protocol
TASK-004  OpenAPI
TASK-005  Tauri IPC
TASK-006  State Machine
TASK-007  Minimal JWT
```

完成：

```text
Contract Freeze
```

然后启动：

```text
┌───────────────────────────────┐
│ Agent 1                       │
│ Rust Core                     │
└───────────────────────────────┘

┌───────────────────────────────┐
│ Agent 2                       │
│ Go Server                     │
└───────────────────────────────┘

┌───────────────────────────────┐
│ Agent 3                       │
│ Vue Mock                      │
└───────────────────────────────┘

┌───────────────────────────────┐
│ Agent 4                       │
│ Blob Specialist               │
└───────────────────────────────┘

┌───────────────────────────────┐
│ Agent 5                       │
│ QA / Integration              │
└───────────────────────────────┘
```

---

# 56. 最终开发路线

```text
Phase 0
Architecture / Contract Freeze
        │
        ▼
Phase 1
Parallel Foundation
        │
        ▼
╔══════════════════════════════╗
║ Sync Vertical Slice ⭐       ║
║                              ║
║ A → Push → Server → Pull → B ║
╚══════════════╤═══════════════╝
               │
               ▼
Phase 3
Sync Hardening
        │
        ├── Conflict
        ├── Version Guard
        ├── Tombstone
        ├── Retry
        ├── Crash Recovery
        └── Device
               │
               ▼
Phase 4
Blob Integration
               │
               ▼
Phase 5
UI Integration
               │
               ▼
Phase 6
Auth / Device Complete
               │
               ▼
Phase 7
E2E
               │
               ▼
Phase 8
Performance
               │
               ▼
Phase 9
Deploy / Release
```

---

# 57. 开发纪律

> **Schema 是唯一真相源。**

> **API 是唯一服务端接口契约。**

> **IPC 是唯一客户端 UI/Rust 接口契约。**

> **Change Protocol 是同步唯一真相源。**

> **Editor Save 与 Sync 必须解耦。**

> **Blob 与 Entity Sync 必须解耦。**

> **Change Log 与 Outbox 职责分离。**

> **不得使用 `is_synced` 作为同步环路控制机制。**

> **不得在 `entity_changes` 中擅自增加 `instance_id`。**

> **AI Agent 可以并行实现，但不能并行定义架构。**

> **任何共享 Contract 变更必须经过 Agent 0。**

> **共享文件系统下，以文件所有权而非 Git Worktree 实现 Agent 隔离。**

> **同一文件存在并发修改需求时必须串行。**

> **先保证数据正确，再优化性能。**

> **先实现 Sync Vertical Slice，再扩展产品功能。**

---

# 58. 当前状态

```text
Architecture       ✅ v1.1
Development Plan   ✅ v1.2

Schema Design      ✅ 待 TASK-002 最终冻结
Sync Design        ✅ 待 TASK-003 最终冻结
API Design         ✅ 待 TASK-004 最终冻结
IPC Design         ✅ 待 TASK-005 最终冻结
Agent Split        ✅
File Ownership     ✅
Parallel Strategy  ✅

Current Phase:
Phase 0 — Architecture / Contract Freeze
```

---

# 59. 开工条件

满足以下条件后正式进入并行开发：

```text
□ docs/schema/common.sql
□ docs/schema/client.sql
□ docs/schema/server.sql
□ docs/openapi.yaml
□ docs/ipc.md
□ docs/change-protocol.md
□ State Machine
□ Minimal JWT Contract
□ legacy/ 目录隔离完成
□ Agent 文件所有权确认
```

完成后：

> **正式启动 Agent 1～5 并行开发。**

---

# 60. 版本说明

```text
架构设计版本：
LightNote 技术架构设计 v1.1

开发计划版本：
LightNote v1.2 AI 多代理开发计划
```

v1.2 相对于上一版主要修正：

```text
1. 移除 is_synced 概念残留
2. 移除 instance_id
3. 明确 Change Log / Outbox 职责分离
4. 强化 Schema Single Source of Truth
5. 明确 OpenCode 共享文件系统并行策略
6. 移除 Git Worktree 强制依赖
7. 增加 Agent 文件所有权规则
8. 明确公共 Contract 只能由 Agent 0 管理
9. 保持 Editor Save / Sync 解耦
10. 保持 Minimal JWT 前置到 Sync Vertical Slice
```

---

# 61. Agent 0 执行裁定（2026-08-11，Phase 0~3 完成后）

## 61.1 Blob 并入 Sync Engine（已批准）

```text
sync_once()
   │
   ├─ push changes
   ├─ pull changes
   ├─ apply changes
   ├─ enqueue_missing_blobs()
   └─ download_queue.run()
```

**硬性约束：**

```text
1. Blob 下载失败绝不能让本次同步失败（异步、非阻塞、独立 retry/backoff）
2. Note Change 应用成功即推进 pull cursor，不等待 Blob
3. Blob 失败 → 队列重试 → 连续 5 次 FAILED，不阻塞后续同步
```

实现方式：`Core::sync_trigger_with_blob(engine, blob_transport)` 编排；
`sync_trigger`（纯同步）保持不变，供测试/无 Blob 场景使用。

## 61.2 UI 接真实 IPC：最小 Vertical Slice（已批准）

```text
Vue UI → Frontend Service → Tauri IPC → Rust Command → NoteService → Repository → SQLite
```

先打通 5 个最小 IPC，再接 4 个：

```text
第一批：list_notes / get_note / create_note / update_note / delete_note
第二批：search_notes / get_tree / sync_status / trigger_sync
```

Mock 不一次性替换；`VITE_USE_MOCK` 单行切换（docs/ipc.md §14）。

## 61.3 修订后的 Phase 划分

```text
Phase 4  Blob→Sync Engine / Tauri IPC Contract / Rust Command Layer / Vue→IPC 最小链路
Phase 5  Tree / Editor / Search / Sync Status / Blob Attachment / Command Palette
Phase 6  Auth / Device / Settings
Phase 7  E2E / 双设备 / 网络异常 / 数据恢复
Phase 8  Windows Packaging / Server Deploy / Backup
Phase 9  Final Acceptance
```

## 61.4 8 项 Smoke Test（非阻塞 Gate，与 Phase 4 并行）

```text
□ 创建 Note
□ 修改 Note
□ 关闭应用 → 重新打开 Note 仍存在
□ 第二客户端能看到 Note
□ 第二客户端修改后第一客户端能同步
□ 删除 / 冲突结果符合预期
□ 带附件的 Note 能正常打开
□ 搜索命中
```

## 61.5 Real User Path（最终验收指标）

```text
启动 → 创建笔记 → 输入 Markdown → 自动保存 → 关闭 → 重新打开
→ 搜索 → 移动目录 → 附件 → 另一设备同步 → 编辑 → 返回第一设备
```

此流程完整跑通，才意味着 LightNote 从同步引擎项目变成笔记软件。

---

**LightNote v1.2 AI Multi-Agent Development Plan**

**Status: Ready for Development**

**Architecture Baseline: LightNote Technical Architecture v1.1**

**Development Model: OpenCode Shared-FS Multi-Agent Parallel Development**