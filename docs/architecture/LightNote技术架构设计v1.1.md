# LightNote 技术架构设计 v1.1

> **项目名称：** LightNote  
> **文档版本：** v1.1  
> **文档状态：** 架构设计  
> **基于版本：** v1.0  
> **核心变更：** 重构同步模型、明确冲突处理、补充 Blob/FTS/设备同步设计、增加同步时序图与状态机

---

# 1. 版本变更说明

v1.1 针对 v1.0 架构评审中发现的问题进行调整。

## 1.1 主要变更

| 编号 | 变更 |
|---|---|
| 1 | `entity_changes` 与 `sync_outbox` 职责分离 |
| 2 | 增加 `origin_device_id` |
| 3 | 增加 `base_version` |
| 4 | 明确实体快照型 Change Payload |
| 5 | 明确 Push / Commit / Pull 流程 |
| 6 | 明确同步环路处理机制 |
| 7 | 明确冲突检测与冲突副本 |
| 8 | FTS5 明确为本地派生数据 |
| 9 | Blob 改为内容寻址 |
| 10 | 增加 `devices / sync_state / device_sync_state` |
| 11 | 增加同步状态机 |
| 12 | API 统一增加 `/api/v1` |
| 13 | 明确树节点及正文懒加载 |
| 14 | 增加异常恢复与幂等规则 |
| 15 | 增加 Pull Version Guard：本地实体版本高于 Change 时禁止应用快照（§32.1） |
| 16 | 增加 Device Identity Binding：device_id 一律从 JWT 获取，不信任客户端自报（§9.2） |
| 17 | 明确 Authentication Model：v1.1 = 单用户 + 多设备，users → devices → sync_state（§9.1） |
| 18 | 增加 Blob Lazy Download：实体 Change 可先应用，缺失 Blob 由后台队列下载并校验落盘（§44.1） |
| 19 | 明确 Server Sequence 分配规则：AUTOINCREMENT 严格单调、回滚只产生 gap、entity_changes 永不物理删除、Pull 按 `>` 过滤（§22.1 / §51） |

---

# 2. 核心架构原则

LightNote v1.1 遵循以下原则：

1. **Local First**
2. **客户端拥有完整数据副本**
3. **Server 保存完整数据副本**
4. **实体修改与本地 Change Log 在同一事务中提交**
5. **实体修改与 Outbox 在同一事务中提交**
6. **Pull 应用的 Change 不进入 Outbox**
7. **Change ID 保证幂等**
8. **Server Sequence 保证同步顺序**
9. **Base Version 用于并发冲突检测**
10. **冲突默认保留，不静默丢失**
11. **FTS5 属于本地派生数据，不参与同步**
12. **Blob 使用内容寻址**
13. **同步失败不能影响本地编辑**
14. **同步最终一致**
15. **所有同步操作必须支持断网、重试、重复请求和进程崩溃恢复**
16. **Pull 应用 Change 前必须做 Version Guard：本地版本高于 Change 版本则禁止应用**
17. **设备身份以 JWT 为准：device_id 从 JWT 获取，服务端不信任客户端自报**
18. **v1.1 采用单用户 + 多设备认证模型：users → devices → sync_state**
19. **Blob 采用懒下载：实体 Change 可先应用，缺失 Blob 后台下载，SHA-256 校验后原子落盘**
20. **Server Sequence 必须严格单调且永不重复：AUTOINCREMENT 分配、与实体写入同事务、entity_changes 永不物理删除、Pull 容忍 gap**

---

# 3. 总体架构

```text
                         LightNote
                             │
             ┌───────────────┴───────────────┐
             │                               │
        Desktop Client                  LightNote Server
             │                               │
          Tauri 2                         Go Server
             │                               │
      ┌──────┴──────┐                ┌───────┴────────┐
      │             │                │                │
    Vue 3          Rust           REST API        Sync Engine
      │             │                │                │
      └──────┬──────┘                └────────┬───────┘
             │                                │
          SQLite                           SQLite
             │                                │
       ┌─────┴─────┐                    ┌────┴─────┐
       │           │                    │          │
    Entities   Sync Data             Entities   Changes
       │           │
       │       ┌───┴────┐
       │       │        │
       │     Outbox   Cursor
       │
       └───────┬────────────── Sync ──────────────┘
               │
             Blob
               │
          File Storage
```

---

# 4. 同步数据模型

同步系统由四类数据组成：

```text
Entity
Change Log
Outbox
Cursor
```

---

## 4.1 Entity

真正的数据：

```text
notes
branches
attributes
blobs
```

---

## 4.2 Entity Change Log

记录：

> “这个实体曾经发生过什么变化。”

```text
entity_changes
```

它是同步历史。

---

## 4.3 Sync Outbox

记录：

> “这个设备还有哪些 Change 没发送到 Server。”

```text
sync_outbox
```

它是发送队列。

---

## 4.4 Sync Cursor

记录：

> “这个设备已经处理到 Server 的哪个 Change。”

```text
sync_state.last_server_sequence
```

---

# 5. 为什么必须拆分 Change Log 和 Outbox

不采用：

```text
entity_changes.is_synced
```

作为唯一同步机制。

原因是：

```text
Change Log
```

和：

```text
Outbox
```

本质上是两个不同概念。

例如：

```text
A 修改 Note
```

产生：

```text
entity_changes
+
sync_outbox
```

而：

```text
B Pull A 的 Change
```

只产生：

```text
entity_changes
```

不产生：

```text
sync_outbox
```

因此：

```text
本地修改
    ↓
Change Log + Outbox

远端 Pull
    ↓
Change Log
```

不会产生：

```text
Pull → Outbox → Push → Pull
```

同步环路。

---

# 6. Entity Change Schema

```sql
CREATE TABLE entity_changes (
    change_id          TEXT PRIMARY KEY,
    origin_device_id   TEXT NOT NULL,

    entity_type        TEXT NOT NULL,
    entity_id          TEXT NOT NULL,

    operation          TEXT NOT NULL,

    base_version       INTEGER NOT NULL,
    version            INTEGER NOT NULL,

    server_sequence    INTEGER,

    content_hash       TEXT,

    payload            TEXT NOT NULL,

    created_at         INTEGER NOT NULL
);
```

---

# 7. Sync Outbox Schema

```sql
CREATE TABLE sync_outbox (
    change_id     TEXT PRIMARY KEY,
    created_at    INTEGER NOT NULL,

    FOREIGN KEY(change_id)
        REFERENCES entity_changes(change_id)
);
```

Outbox 中只保存：

```text
change_id
```

具体 Change 内容从：

```text
entity_changes
```

读取。

---

# 8. Sync State

客户端：

```sql
CREATE TABLE sync_state (
    client_id              TEXT PRIMARY KEY,

    last_server_sequence   INTEGER NOT NULL DEFAULT 0,

    updated_at              INTEGER NOT NULL
);
```

---

# 9. Devices

服务端：

```sql
CREATE TABLE devices (
    device_id       TEXT PRIMARY KEY,
    device_name     TEXT NOT NULL,
    device_type     TEXT,
    last_seen       INTEGER,
    created_at      INTEGER NOT NULL
);
```

支持：

```text
Windows PC
MacBook
Laptop
```

等设备管理。

---

## 9.1 Authentication Model

v1.1 认证模型：

> **单用户 + 多设备**

关系链：

```text
users → devices → sync_state
```

```sql
CREATE TABLE users (
    user_id       TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at    INTEGER NOT NULL
);
```

v1.1 服务端只允许一个用户（默认账号），但保留 users 表：

- 数据库结构与未来多用户版本兼容
- 同步、设备、Blob 均按 user_id 隔离
- 未来多用户只需增加注册与权限校验，数据模型不变

登录流程：

```text
POST /api/v1/auth/login
  ├── 校验 username + password
  ├── 校验 device_id + device_name
  ├── 注册 / 更新 devices 表
  └── 签发 JWT（含 user_id + device_id claims）
```

Token 结构：

```text
Access Token  +  Refresh Token
```

Access Token：

```text
短生命周期（默认 2 小时）
```

Refresh Token：

```text
长期有效，绑定 device_id，可单独吊销
```

吊销设备：

```text
服务端删除 devices 记录
→ 该设备的 Refresh Token 立即失效
→ 撤销设备成功
```

---

## 9.2 Device Identity Binding

> **服务端不信任客户端自报的 device_id。**

设备身份一律从 JWT 获取：

```text
JWT claims
  ├── user_id
  └── device_id
```

规则：

```text
Push / Pull / Blob API
    ↓
服务端从 JWT 解析 device_id
    ↓
不使用请求体中的 deviceId 字段
```

因此：

- 客户端无法伪造其他设备的身份
- 所有 Change 的 origin_device_id 以 JWT 中的 device_id 为准
- device_sync_state 与设备吊销机制可信
- 请求体中不再需要携带 deviceId 字段（可保留用于日志，不参与校验）

Push 请求中 Change 的 origin_device_id 处理：

```text
服务端收到 Change
    ↓
origin_device_id = JWT 中的 device_id
    ↓
写入 entity_changes
```

客户端本地记录真实 origin_device_id 不变，服务端覆盖为认证身份。

---

# 10. Server Device Sync State

服务端维护：

```sql
CREATE TABLE device_sync_state (
    device_id              TEXT PRIMARY KEY,

    last_server_sequence   INTEGER NOT NULL DEFAULT 0,

    last_seen               INTEGER,

    updated_at              INTEGER NOT NULL
);
```

用于未来：

- Tombstone GC
- Change Log GC
- 长期离线设备检测
- 设备同步状态展示

---

# 11. Change ID

客户端生成全局唯一：

```text
UUIDv7 / ULID
```

例如：

```text
01K2ABCDEF...
```

Change ID 永久唯一。

服务端根据：

```text
change_id
```

实现幂等。

---

# 12. Origin Device

每个 Change 保存：

```text
origin_device_id
```

例如：

```text
Change A
origin_device_id = DEVICE-A
```

Pull 到其他设备后：

```text
origin_device_id = DEVICE-A
```

不会修改。

用途：

- 识别变更来源
- 调试
- 冲突分析
- 设备同步统计
- 日志追踪

---

# 13. Entity Version

每个实体维护：

```text
version
```

例如：

```text
Note N001
version = 10
```

客户端修改：

```text
baseVersion = 10
version = 11
```

因此一个 Change 同时携带：

```text
base_version
version
```

---

# 14. Base Version

`base_version` 表示：

> 客户端开始修改时，它认为实体是什么版本。

例如：

```text
Server
version = 10
```

客户端读取：

```text
baseVersion = 10
```

离线修改后：

```text
baseVersion = 10
version = 11
```

---

# 15. 冲突判断

Server 收到 Change：

```text
baseVersion
```

Server 当前：

```text
currentVersion
```

判断：

```text
baseVersion == currentVersion
```

表示：

```text
无并发冲突
```

否则：

```text
baseVersion != currentVersion
```

表示：

```text
发生并发冲突
```

---

# 16. Change Payload

Change 使用：

> **实体快照**

而不是 SQL 操作。

例如 Note：

```json
{
  "changeId": "01ABC",
  "originDeviceId": "device-A",

  "entityType": "note",
  "entityId": "N001",

  "operation": "UPDATE",

  "baseVersion": 10,
  "version": 11,

  "payload": {
    "title": "Docker 网络",
    "noteType": "text",
    "blobId": "sha256:abcd...",
    "isDeleted": false
  }
}
```

---

# 17. 为什么使用实体快照

不采用：

```json
{
  "sql": "UPDATE notes SET..."
}
```

而采用：

```text
Entity Snapshot
```

原因：

- 客户端与服务端数据库实现可以不同
- 避免 SQL 注入
- 更容易重放
- 更容易调试
- 更容易实现冲突
- 更容易进行数据校验
- 更适合未来协议演进

---

# 18. 本地修改事务

用户修改 Note：

```text
BEGIN
    │
    ├── UPDATE notes
    │
    ├── UPDATE note_fts
    │
    ├── INSERT entity_changes
    │
    └── INSERT sync_outbox
    │
COMMIT
```

必须保证：

> Entity + FTS + Change + Outbox

属于同一个本地事务。

---

# 19. Pull Change 事务

远端 Change：

```text
BEGIN
    │
    ├── Apply Entity
    │
    ├── UPDATE note_fts
    │
    ├── INSERT entity_changes
    │
    └── UPDATE sync_state
    │
COMMIT
```

但是：

```text
禁止 INSERT sync_outbox
```

因此：

```text
Pull
 ↓
Apply
 ↓
不会重新 Push
```

---

# 20. Push 总体流程

```text
Client
   │
   │ 1. Read Outbox
   ▼
sync_outbox
   │
   │ 2. Load Change
   ▼
entity_changes
   │
   │ 3. POST /api/v1/sync/push
   ▼
Server
   │
   │ 4. Idempotency Check
   ▼
   │
   │ 5. Version Check
   ▼
   │
   ├──────────────┐
   │              │
 No Conflict    Conflict
   │              │
   ▼              ▼
 Commit        Conflict Handler
   │              │
   └──────┬───────┘
          ▼
     serverSequence
          │
          ▼
       Response
          │
          ▼
Client Remove Outbox
```

---

# 21. Push Sequence Diagram

```text
Client                         Server
  │                              │
  │ Read sync_outbox             │
  │─────────────────────────►   │
  │                              │
  │ Load entity_change           │
  │                              │
  │ POST /api/v1/sync/push       │
  │─────────────────────────────►│
  │                              │
  │                        Check changeId
  │                              │
  │                        Check version
  │                              │
  │                        ┌─────┴─────┐
  │                        │           │
  │                     No Conflict  Conflict
  │                        │           │
  │                        ▼           ▼
  │                     Commit    Conflict Handler
  │                        │           │
  │                        └─────┬─────┘
  │                              │
  │                       Assign sequence
  │                              │
  │◄─────────────────────────────│
  │ Push Result                  │
  │                              │
  │ Remove Outbox                │
  │                              │
```

---

# 22. Server Commit

Server必须在一个数据库事务中完成：

```text
BEGIN
    │
    ├── Check change_id
    │
    ├── Check entity
    │
    ├── Check base_version
    │
    ├── Apply entity
    │
    ├── Insert entity_changes
    │
    └── Assign server_sequence
    │
COMMIT
```

---

## 22.1 Server Sequence 分配规则

> server_sequence 是同步顺序的唯一依据，**分配规则必须在实现层硬性保证**，这是实际实现中最容易踩坑的点。

### 规则 1：必须严格单调且永不重复

```text
✅ 使用 SQLite AUTOINCREMENT 生成
   （INTEGER PRIMARY KEY AUTOINCREMENT 或专用序列分配表）

❌ 禁止使用 SELECT MAX(server_sequence) + 1 手动分配
```

原因（已实测验证 SQLite 行为）：

```text
普通 INTEGER PRIMARY KEY（rowid 别名）：
    INSERT 100 提交
    INSERT 101 回滚
    INSERT NULL → 复用 101
    ↓
    两个不同的 Change 可能拿到同一个 server_sequence
    同步游标将永久丢失其中一个 Change

INTEGER PRIMARY KEY AUTOINCREMENT：
    INSERT 100 提交
    INSERT 101 回滚
    INSERT NULL → 可能复用 101 或取 102（sqlite_sequence 会随事务回滚，
                  具体取值取决于是否仍为最大行；实测两种都可能出现）
    ↓
    但回滚行从未可见，任何已提交序列仍严格单调、永不重复
```

> **实测修正（2026-08-11，Agent 2 验证）：** SQLite 的 `sqlite_sequence` 计数会随事务回滚，回滚的分配值可能被下一次分配复用——但未提交值对其他事务不可见，因此**已提交的 server_sequence 必然严格单调且永不重复**。真正必须防住的是「已提交行的 rowid 被复用」（即物理删除），由规则 3 保证。测试应断言「已提交序列单调不重复」，而非「回滚后不复用」。

### 规则 2：分配必须与实体写入同一事务

```text
BEGIN
    ├── Apply entity
    ├── Insert entity_changes（含 AUTOINCREMENT 分配的 sequence）
    └── COMMIT
```

回滚后该 sequence 的分配值可能被复用（见规则 1 实测修正），但**未提交值不可见**，已提交序列不受影响；产生的 gap 是正常现象。

### 规则 3：entity_changes 永不物理删除

```text
禁止 DELETE FROM entity_changes
```

rowid 复用的危险场景：

```text
Change X 以 seq=101 提交，客户端已拉取（cursor=101）
    ↓
某操作物理删除了该行
    ↓
Change Y 提交时复用 seq=101
    ↓
客户端 after=101 过滤 → 永远看不到 Change Y
```

v1.5 的 Change Log GC 实施前必须解决该约束（例如：GC 只清理所有设备水位以下的最旧记录，且依赖 device_sync_state 水位确认）。

### 规则 4：单实例写者约束

```text
SQLite 单写者 + AUTOINCREMENT
    ↓
全局单调序列仅在单服务端实例下成立
```

多实例水平扩展需要分段/雪花序列，v1.x 明确不引入（见 §71）。

---

# 23. Server Commit 成功条件

正常更新：

```text
baseVersion == currentVersion
```

例如：

```text
Client:
baseVersion = 10

Server:
currentVersion = 10

Result:
SUCCESS
```

服务器：

```text
version = 11
serverSequence = 10086
```

---

# 24. Server Commit 时序图

```text
Client                    Server DB
  │                           │
  │ Push Change               │
  │──────────────────────────►│
  │                           │
  │                       BEGIN
  │                           │
  │                    Check changeId
  │                           │
  │                    Check currentVersion
  │                           │
  │                    baseVersion == current
  │                           │
  │                    UPDATE Entity
  │                           │
  │                    INSERT Change
  │                           │
  │                    Assign Sequence
  │                           │
  │                       COMMIT
  │                           │
  │◄──────────────────────────│
  │ success / sequence        │
```

---

# 25. Push 幂等

如果客户端发送：

```text
changeId = ABC
```

服务器已经处理：

```text
ABC
```

再次收到：

```text
ABC
```

服务器不得再次修改实体。

直接返回：

```json
{
  "changeId": "ABC",
  "status": "ALREADY_APPLIED",
  "serverSequence": 10086
}
```

---

# 26. 网络超时恢复

例如：

```text
Client
 ↓
Push
 ↓
Server Commit SUCCESS
 ↓
Network timeout
 ↓
Client 未收到结果
```

客户端不知道服务器是否成功。

因此：

```text
Outbox
```

不能立即删除。

下次继续 Push：

```text
ABC
```

Server：

```text
changeId ABC 已存在
```

返回：

```text
ALREADY_APPLIED
```

客户端：

```text
删除 Outbox
```

这就是完整的幂等恢复机制。

---

# 27. Outbox 状态机

Outbox 状态：

```text
PENDING
   │
   ▼
SENDING
   │
   ├───────────────┐
   │               │
 SUCCESS         ERROR
   │               │
   ▼               ▼
 REMOVE          PENDING
```

详细：

```text
                 ┌──────────────┐
                 │              │
                 ▼              │
             ┌────────┐         │
             │ PENDING │◄────────┤
             └───┬────┘         │
                 │              │
                 │ send         │ retry
                 ▼              │
             ┌────────┐         │
             │SENDING │         │
             └───┬────┘         │
                 │              │
        ┌────────┼────────┐     │
        │        │        │     │
        ▼        ▼        ▼     │
     SUCCESS  CONFLICT   ERROR  │
        │        │        │     │
        ▼        ▼        └─────┘
      REMOVE   RESOLVED
                  │
                  ▼
                REMOVE
```

> `SENDING` 不建议永久持久化为唯一状态依据。进程崩溃后超过超时时间的 `SENDING` 记录必须自动恢复为 `PENDING`。

---

# 28. Pull 总体流程

```text
Client
   │
   │ lastServerSequence
   ▼
GET /api/v1/sync/changes
   │
   ▼
Server
   │
   │ changes > cursor
   ▼
Client
   │
   ▼
BEGIN
   │
   ├── Apply Entity
   ├── Update FTS
   ├── Record Change
   └── Update Cursor
   │
COMMIT
```

---

# 29. Pull Sequence Diagram

```text
Client                         Server
  │                              │
  │ lastSequence = 100           │
  │                              │
  │ GET /api/v1/sync/changes     │
  │ after=100                    │
  │─────────────────────────────►│
  │                              │
  │                         Query Changes
  │                              │
  │◄─────────────────────────────│
  │ 101,102,103                  │
  │                              │
  │ BEGIN                        │
  │                              │
  │ Apply 101                    │
  │ Apply 102                    │
  │ Apply 103                    │
  │                              │
  │ Update FTS                   │
  │                              │
  │ Save Change Log              │
  │                              │
  │ cursor = 103                 │
  │                              │
  │ COMMIT                       │
  │                              │
```

---

# 30. Pull 的关键原则

Pull Change：

```text
必须：
    Apply Entity
    Update FTS
    Update Cursor

不能：
    Insert Outbox

禁止：
    local.version > change.version 时应用快照（Pull Version Guard，见 §32.1）
```

这是避免同步环路的核心规则。

---

# 31. Pull 崩溃恢复

例如：

```text
收到：
101
102
103
```

应用：

```text
101 SUCCESS
102 SUCCESS
103 CRASH
```

因为：

```text
Entity + Change + Cursor
```

在同一个事务中：

```text
103 transaction rollback
```

下一次继续：

```text
after=102
```

重新获取：

```text
103
```

因此不会丢数据。

---

# 32. Pull 幂等

如果因为异常：

```text
101
```

被再次 Pull：

服务器：

```text
101
```

客户端发现：

```text
change_id already exists
```

可以跳过实体应用。

但必须确保：

```text
cursor
```

继续推进。

---

## 32.1 Pull Version Guard

> **本地实体版本高于 Change 版本时，禁止应用 Change 快照。**

这是防止"旧快照回退新数据"的防御性规则。

判断：

```text
本地实体 version = 12
Change 版本      = 11
    ↓
local.version > change.version
    ↓
禁止应用 Change 快照
```

应用前完整判断顺序：

```text
Pull 收到 Change
    ↓
change_id 已存在？
    ├── YES → 跳过实体应用，仅推进 cursor
    └── NO  ↓
         local.version > change.version？
              ├── YES → 跳过实体应用，仅记录 change，推进 cursor
              └── NO  → 正常应用快照
```

说明：

| 场景 | local.version vs change.version | 行为 |
|---|---|---|
| 本地版本更高 | `>` | 禁止应用，本地数据为准 |
| 版本相同 | `==` | 正常应用（幂等） |
| 本地版本更低 | `<` | 正常应用（更新到远端版本） |

Version Guard 与 change_id 去重是**两层防御**：

```text
change_id 去重
    ↓
处理"同一个 Change 重复送达"

Version Guard
    ↓
处理"旧 Change 晚于新 Change 到达"
```

即使未来 Change Log 被 GC（v1.5），Version Guard 仍能防止数据回退。

注意：

```text
跳过应用 ≠ 跳过记录
```

跳过实体应用时仍然需要：

```text
INSERT entity_changes
    ↓
UPDATE sync_state（推进 cursor）
```

保证服务端游标推进，不会死循环。

---

# 33. Conflict

冲突条件：

```text
baseVersion != serverCurrentVersion
```

例如：

```text
Server:
version = 11

Client:
baseVersion = 10
```

说明：

```text
Client 基于旧版本修改
```

---

# 34. Conflict 处理原则

v1.1：

> **Last Commit Wins + Conflict Preservation**

即：

1. 当前服务器版本保留为主版本
2. 后提交版本不直接丢弃
3. 后提交内容生成 Conflict Copy
4. Conflict Copy 通过正常 Change Log 同步

---

# 35. Conflict 时序图

```text
Client A                Server                 Client B
   │                       │                       │
   │ baseVersion=10        │                       │
   │──────────────────────►│                       │
   │                       │                       │
   │                       │ version=10            │
   │                       │───────►              │
   │                       │                       │
   │                       │                       │ modify
   │                       │◄──────────────────────│
   │                       │                       │
   │                       │ version=11            │
   │                       │                       │
   │                       │                       │
   │ Push A                │                       │
   │ baseVersion=10        │                       │
   │──────────────────────►│                       │
   │                       │                       │
   │                       │ current=11            │
   │                       │                       │
   │                       │ CONFLICT              │
   │                       │                       │
   │                       │ Create Conflict Copy  │
   │                       │                       │
   │                       │ Record Change         │
   │                       │                       │
   │◄──────────────────────│                       │
   │ CONFLICT              │                       │
```

---

# 36. Conflict Copy

Conflict Copy 是一个新的 Note。

例如：

```text
原 Note:

Docker 网络
```

产生：

```text
Docker 网络（冲突副本）
```

Conflict Copy 保存：

```text
conflict_of_note_id
```

建议增加：

```sql
ALTER TABLE notes
ADD COLUMN conflict_of_note_id TEXT;
```

正常 Note：

```text
conflict_of_note_id = NULL
```

冲突副本：

```text
conflict_of_note_id = 原 Note ID
```

---

# 37. Conflict Copy 的同步

Conflict Copy 是真正的数据实体。

因此：

```text
Create Conflict Note
        │
        ├── notes
        ├── blob
        ├── entity_changes
        └── serverSequence
```

其他设备 Pull 后：

```text
正常创建冲突 Note
```

不会产生新的 Outbox。

---

# 38. Delete Conflict

删除也参与版本检测。

例如：

```text
A:
删除 Note
baseVersion=10

B:
修改 Note
baseVersion=10
```

谁先提交谁成为：

```text
version=11
```

后提交者：

```text
baseVersion=10
currentVersion=11
```

触发 Conflict。

删除与修改冲突时：

> **不得静默覆盖。**

需要保留冲突版本。

---

# 39. Tombstone

删除：

```text
is_deleted = 1
```

而不是立即：

```sql
DELETE FROM notes
```

Tombstone：

```text
Note
 │
 └── isDeleted=true
```

长期离线设备重新上线后：

```text
Pull Tombstone
```

可以正确删除本地可见状态。

---

# 40. Blob 内容寻址

v1.1 定义：

```text
blob_id = SHA-256(content)
```

例如：

```text
sha256:
8f14e45fceea167a5a36...
```

Blob ID 即内容 Hash。

---

# 41. Blob Schema

```sql
CREATE TABLE blobs (
    blob_id        TEXT PRIMARY KEY,
    size           INTEGER NOT NULL,
    mime_type      TEXT,
    storage_type   TEXT NOT NULL DEFAULT 'file',
    storage_path   TEXT NOT NULL,
    created_at     INTEGER NOT NULL
);
```

不再需要：

```text
content_hash
```

因为：

```text
blob_id == content_hash
```

---

# 42. Blob 上传

```text
POST /api/v1/blobs/init
```

客户端发送：

```json
{
  "blobId": "sha256:abcd...",
  "size": 10485760,
  "mimeType": "application/pdf"
}
```

Server：

```text
Blob Exists
    │
    ├── YES → Already Exists
    │
    └── NO  → Create Upload Session
```

---

# 43. Blob Chunk

```text
PUT /api/v1/blobs/{blobId}/chunks/{index}
```

支持：

- 断点续传
- 重复 Chunk
- Chunk 校验
- 并行上传

---

# 44. Blob Complete

```text
POST /api/v1/blobs/{blobId}/complete
```

Server：

```text
所有 Chunk
    ↓
计算 SHA-256
    ↓
验证 blobId
    │
    ├── 一致 → COMMIT
    └── 不一致 → REJECT
```

---

## 44.1 Blob Lazy Download

> **Pull 应用实体 Change 不等待 Blob 下载完成。**

实体元数据与应用立即成功，Blob 内容按需后台下载。

```text
Pull 应用 Change
    │
    ├── Entity 立即应用（成功）
    │
    └── Blob 本地缺失？
         ├── YES → 加入下载队列
         └── NO  → 无需处理
```

本地下载队列：

```sql
CREATE TABLE blob_download_queue (
    blob_id      TEXT PRIMARY KEY,
    status       TEXT NOT NULL DEFAULT 'PENDING',
    retry_count  INTEGER NOT NULL DEFAULT 0,
    created_at   INTEGER NOT NULL
);
```

后台下载器：

```text
队列取 Blob
    ↓
GET /api/v1/blobs/{blobId}
    ↓
写入临时文件
    ↓
计算 SHA-256
    ↓
校验 blobId
    ├── 一致 → 原子 rename 落盘，删除队列记录
    └── 不一致 → 丢弃临时文件，标记 FAILED，重试
```

原子落盘：

```text
下载到临时文件（同目录 .tmp）
    ↓
SHA-256 校验通过
    ↓
rename 到最终路径
```

校验失败策略：

```text
重试（指数退避）
    ↓
连续失败 N 次（默认 5 次）
    ↓
标记 FAILED
    ↓
UI 提示下载失败，不阻塞其他功能
```

下载触发时机：

```text
后台队列轮询
打开笔记时若 Blob 缺失 → 立即插入队列并优先下载
```

优先级：

```text
用户当前打开的笔记 Blob  >  后台队列其余 Blob
```

---

# 45. FTS5

FTS5 属于：

> **本地派生数据**

不进入同步协议。

```text
notes
  │
  ▼
note_fts
```

---

# 46. FTS5 更新

本地修改：

```text
BEGIN

UPDATE notes
UPDATE note_fts
INSERT entity_changes
INSERT sync_outbox

COMMIT
```

远程 Pull：

```text
BEGIN

UPDATE notes
UPDATE note_fts
INSERT entity_changes
UPDATE sync_state

COMMIT
```

---

# 47. FTS 重建

如果 FTS 损坏：

```text
DROP note_fts
```

重新：

```text
notes
  ↓
Rebuild
  ↓
note_fts
```

因为 FTS 是派生数据，不需要从服务器同步。

---

# 48. API Version

所有 API 使用：

```text
/api/v1
```

例如：

```text
/api/v1/auth/login

/api/v1/notes

/api/v1/branches

/api/v1/attributes

/api/v1/sync/push

/api/v1/sync/changes

/api/v1/blobs
```

未来允许：

```text
/api/v2
```

与 v1 并存。

---

# 49. Sync API

## Push

```http
POST /api/v1/sync/push
```

请求：

```json
{
  "deviceId": "DEVICE-A",
  "changes": [
    {
      "changeId": "01ABC",
      "originDeviceId": "DEVICE-A",

      "entityType": "note",
      "entityId": "N001",

      "operation": "UPDATE",

      "baseVersion": 10,
      "version": 11,

      "contentHash": "sha256:abcd",

      "payload": {
        "title": "Docker 网络",
        "noteType": "text",
        "blobId": "sha256:abcd",
        "isDeleted": false
      }
    }
  ]
}
```

> **注意**：请求体中的 `deviceId` 与 Change 内的 `originDeviceId` 仅供日志与调试。服务端一律以 JWT 中的 `device_id` 为准（Device Identity Binding，见 §9.2），写入 `entity_changes` 时覆盖为认证身份。

---

# 50. Push Response

```json
{
  "results": [
    {
      "changeId": "01ABC",
      "status": "APPLIED",
      "serverSequence": 10086
    }
  ]
}
```

状态：

```text
APPLIED
ALREADY_APPLIED
CONFLICT
INVALID
RETRYABLE_ERROR
```

---

# 51. Pull API

```http
GET /api/v1/sync/changes?after=10086&limit=500
```

返回：

```json
{
  "changes": [
    {
      "serverSequence": 10087,
      "changeId": "01DEF",
      "originDeviceId": "DEVICE-B",

      "entityType": "note",
      "entityId": "N002",

      "operation": "UPDATE",

      "version": 5,

      "payload": {}
    }
  ],

  "nextSequence": 10087,

  "hasMore": false
}
```

### Gap 处理规则

```text
server_sequence 允许不连续（回滚产生 gap 是正常现象）
    ↓
服务端查询：WHERE server_sequence > ? ORDER BY server_sequence LIMIT n
    ↓
客户端不得假设序列连续
```

客户端 Pull 语义：

```text
client: after = 10085
server 已提交序列: 10082, 10086, 10088, 10089
（10083~10085、10087 为已回滚事务留下的 gap）
    ↓
返回: 10086, 10088
nextSequence = 10088
hasMore = true（还有 10089）
    ↓
客户端应用后 cursor = 10088，继续请求 after=10088
```

注意：

```text
❌ 不要用 nextSequence = after + 1 推断遗漏
✅ cursor 只记录"本批次最后一条已成功应用的 server_sequence"
✅ 是否还有下一页一律以 hasMore（或查询是否存在 > cursor 的行）为准
```

---

# 52. Sync Cursor

客户端维护：

```text
lastServerSequence
```

规则：

```text
Pull 成功
    ↓
Apply Change
    ↓
SQLite COMMIT
    ↓
cursor = nextSequence
```

不能提前更新 Cursor。

补充规则：

```text
cursor 只前进、不回退
cursor 与批量应用的 Change 在同一事务提交（见 §65）
序列有 gap 时 cursor 仍是"最后一条已应用序列"，不是假设的连续值
```

---

# 53. 完整同步流程

```text
┌─────────────────────────────────────────────┐
│                 Sync Engine                 │
└─────────────────────────────────────────────┘

                 Network Available
                        │
                        ▼
                ┌─────────────┐
                │ PUSH OUTBOX │
                └──────┬──────┘
                       │
                       ▼
                   Server
                       │
              ┌────────┴────────┐
              │                 │
           Success           Conflict
              │                 │
              ▼                 ▼
          Remove Outbox    Create Conflict
              │                 │
              └────────┬────────┘
                       │
                       ▼
                 PULL CHANGES
                       │
                       ▼
                  Apply Changes
                       │
                       ▼
                    Update
                     FTS
                       │
                       ▼
                 Update Cursor
                       │
                       ▼
                     DONE
```

---

# 54. 推荐 Push/Pull 顺序

默认：

```text
Push
 ↓
Pull
```

原因：

```text
本地 Change
↓
优先提交 Server
↓
再拉取其他设备变化
```

可以减少：

```text
自己修改
+
别人修改
```

同时发生时的复杂度。

---

# 55. 为什么不是 Pull → Push

如果：

```text
Pull
 ↓
Push
```

可能导致：

```text
本地旧状态
 ↓
先拉远端
 ↓
本地未提交 Change
 ↓
复杂 Merge
```

因此 v1.1 默认：

> **Push First, Pull Second**

但两者最终都必须具备独立恢复能力。

---

# 56. 自动同步触发条件

同步触发：

```text
应用启动
网络恢复
定时器
手动同步
本地有新 Change
```

默认定时：

```text
60 秒
```

推荐增加：

```text
本地修改后 2~5 秒 debounce
```

避免：

```text
用户连续输入
↓
每次按键
↓
Push
```

---

# 57. 同步状态机

客户端同步状态：

```text
IDLE
 │
 │ trigger
 ▼
PREPARING
 │
 ▼
PUSHING
 │
 ├───────────────┐
 │               │
 ▼               ▼
PULLING        WAIT_RETRY
 │               │
 ▼               │
COMPLETED ◄──────┘
```

完整状态：

```text
                    ┌──────────────┐
                    │              │
                    ▼              │
                 ┌──────┐         │
                 │ IDLE │         │
                 └──┬───┘         │
                    │             │
                  trigger         │
                    ▼             │
               ┌──────────┐       │
               │PREPARING │       │
               └────┬─────┘       │
                    │             │
                    ▼             │
               ┌──────────┐       │
               │ PUSHING  │       │
               └────┬─────┘       │
                    │             │
          ┌─────────┼─────────┐   │
          │         │         │   │
       success   conflict   error │
          │         │         │   │
          │         │         └───┤
          │         │             │
          └────┬────┘             │
               │                  │
               ▼                  │
          ┌──────────┐            │
          │ PULLING  │            │
          └────┬─────┘            │
               │                  │
               ▼                  │
          ┌───────────┐           │
          │ COMPLETED │           │
          └─────┬─────┘           │
                │                 │
                ▼                 │
              IDLE ◄──────────────┘
```

---

# 58. Error 状态

错误分为：

```text
NETWORK_ERROR
AUTH_ERROR
SERVER_ERROR
CONFLICT
INVALID_DATA
BLOB_ERROR
DATABASE_ERROR
```

处理：

| 错误 | 策略 |
|---|---|
| NETWORK_ERROR | 自动重试 |
| SERVER 5xx | 指数退避 |
| AUTH_ERROR | Refresh Token |
| CONFLICT | 生成冲突副本 |
| INVALID_DATA | 标记错误，不无限重试 |
| BLOB_ERROR | 单独重试 |
| DATABASE_ERROR | 停止同步并报警 |

---

# 59. Retry

采用指数退避：

```text
1s
2s
4s
8s
16s
30s
60s
```

最大间隔：

```text
60 秒
```

网络恢复后立即重新触发。

---

# 60. 本地数据加载策略

LightNote 不允许启动时：

```text
SELECT *
FROM notes
```

并将全部数据加载进内存。

采用：

> **按需加载 + 懒加载**

---

# 61. Tree Lazy Loading

初始只查询根节点：

```sql
SELECT ...
FROM branches
WHERE parent_note_id = ?
ORDER BY sort_order;
```

展开目录后再查询子节点。

---

# 62. Note Lazy Loading

树节点只加载：

```text
noteId
title
noteType
sortOrder
isDeleted
```

用户真正打开 Note 时才加载：

```text
Blob
Markdown
Attributes
Relations
```

---

# 63. 性能目标

v1.1：

| 指标 | 目标 |
|---|---:|
| 本地数据库打开 | < 500ms |
| 创建 Note | < 50ms |
| 本地保存 | < 50ms |
| 搜索 | < 200ms |
| 10,000 Notes | 正常 |
| 100,000 Notes | 数据库正常，UI 必须懒加载 |
| 单批 Change | 500~1000 |
| Blob Chunk | 4~16MB |
| 服务端 RAM | 目标 < 100MB |
| VPS | 1C / 1GB 可运行 |

---

# 64. 数据一致性保证

每个本地修改必须满足：

```text
Entity
+
FTS
+
Change Log
+
Outbox
```

全部成功。

任何一个失败：

```text
ROLLBACK
```

---

# 65. Pull 一致性保证

每个 Pull Batch：

```text
Entity
+
FTS
+
Change Log
+
Cursor
```

必须：

```text
同一事务
```

因此：

```text
Apply Success
≡
Cursor Advance
```

---

# 66. Server 一致性保证

Server Push：

```text
Entity
+
Change Log
+
Server Sequence
```

必须：

```text
同一事务
```

因此：

```text
Entity 已提交
```

必然意味着：

```text
Change 已记录
```

---

# 67. 同步环路最终解决方案

```text
             Local Change
                  │
                  ▼
       ┌────────────────────┐
       │ Entity             │
       │ Change Log         │
       │ Outbox             │
       └─────────┬──────────┘
                 │
                 ▼
               Push
                 │
                 ▼
              Server
                 │
                 ▼
               Pull
                 │
                 ▼
       ┌────────────────────┐
       │ Entity             │
       │ Change Log         │
       │ FTS                │
       └────────────────────┘

             ❌ 不进入 Outbox
```

因此：

```text
A → Server → B
```

不会变成：

```text
A → Server → B → Server → A
```

---

# 68. 完整同步状态机

```text
                 ┌───────────────┐
                 │     IDLE      │
                 └───────┬───────┘
                         │
                  Local Change /
                  Timer / Network
                         │
                         ▼
                 ┌───────────────┐
                 │   PREPARE     │
                 └───────┬───────┘
                         │
                         ▼
                 ┌───────────────┐
                 │    PUSH       │
                 └───────┬───────┘
                         │
          ┌──────────────┼──────────────┐
          │              │              │
          ▼              ▼              ▼
       SUCCESS        CONFLICT        ERROR
          │              │              │
          │              ▼              │
          │       Conflict Copy         │
          │              │              │
          └──────────────┴──────┐       │
                                │       │
                                ▼       │
                         ┌─────────────┐│
                         │    PULL     ││
                         └──────┬──────┘│
                                │       │
                                ▼       │
                         ┌─────────────┐│
                         │ APPLY BATCH ││
                         └──────┬──────┘│
                                │       │
                                ▼       │
                         ┌─────────────┐│
                         │ UPDATE      ││
                         │ CURSOR      ││
                         └──────┬──────┘│
                                │       │
                                ▼       │
                         ┌─────────────┐│
                         │  COMPLETED  ││
                         └──────┬──────┘│
                                │       │
                                ▼       │
                              IDLE      │
                                        │
                                        ▼
                                  ┌──────────┐
                                  │ RETRY    │
                                  └────┬─────┘
                                       │
                                       └──► PUSH
```

---

# 69. 数据生命周期

```text
用户修改
   │
   ▼
SQLite Entity
   │
   ├── FTS
   │
   ├── Change Log
   │
   └── Outbox
          │
          ▼
        Push
          │
          ▼
       Server
          │
          ├── Entity
          ├── Change Log
          └── Sequence
          │
          ▼
        Pull
          │
          ▼
     Other Client
          │
          ├── Entity
          ├── FTS
          └── Change Log
```

---

# 70. 异常场景矩阵

| 场景 | 处理 |
|---|---|
| Push 前断网 | Outbox 保留 |
| Push 中断网 | Outbox 保留 |
| Server 已提交但响应丢失 | 重试，依赖 changeId 幂等 |
| Pull 中断 | Cursor 不推进 |
| Pull 应用失败 | Transaction Rollback |
| 客户端崩溃 | 未提交事务自动回滚 |
| Server 崩溃 | Transaction Rollback |
| 同一 Change 重复 Push | ALREADY_APPLIED |
| 同一 Change 重复 Pull | changeId 去重 |
| 双设备修改同一 Note | Conflict |
| 删除 vs 修改 | Conflict |
| Blob 上传中断 | Chunk Resume |
| Blob Hash 错误 | Reject |
| 长期离线 | 增量 Pull |
| 设备永久离线 | 暂不 GC Tombstone |
| 服务端事务回滚 | 已提交序列严格单调不重复；未提交分配值不可见，Pull 按 `>` 过滤正常跳过 |
| 服务端序列出现 gap | 正常现象，客户端不得假设连续（见 §51） |
| Change 行被误物理删除 | **禁止**（rowid 复用会导致同步永久丢失，见 §22.1 规则 3） |
| 并发 Push | SQLite 单写者串行化，AUTOINCREMENT 保证序列唯一单调 |
| 多服务端实例 | v1.x 不支持（全局序列依赖单实例写者） |

---

# 71. v1.1 明确不做

以下功能暂不实现：

- CRDT
- 实时协作
- WebSocket 同步
- E2E 加密
- 多人实时编辑
- Change Log 自动 GC
- Tombstone 自动 GC
- 多服务端实例 / 分布式部署（server_sequence 全局单调依赖 SQLite 单写者，多实例需分段/雪花序列，另立方案）
- PostgreSQL
- Redis
- Object Storage
- Kubernetes

---

# 72. 后续演进

## v1.2

```text
Conflict Center
Version History
Import / Export
Automatic Backup
```

## v1.5

```text
Change Log Compaction
Tombstone GC
高级搜索
```

## v2.0

```text
E2E Encryption
Mobile Client
Web Client
```

## v3.0

```text
PostgreSQL
Object Storage
Multi-Tenant
Team Collaboration
SaaS
```

---

# 73. 最终同步架构结论

LightNote v1.1 的同步模型可以概括为：

```text
                ┌──────────────────┐
                │      Entity      │
                └────────┬─────────┘
                         │
              ┌──────────┴──────────┐
              │                     │
       entity_changes          sync_outbox
              │                     │
        变化历史                 待发送
              │                     │
              └──────────┬──────────┘
                         │
                        Push
                         │
                         ▼
                    ┌─────────┐
                    │ Server  │
                    └────┬────┘
                         │
                  serverSequence
                         │
                        Pull
                         │
                         ▼
                    Other Client
```

核心规则：

> **Entity 定义数据本身；Entity Change 定义发生过什么；Outbox 定义还需要发送什么；Server Sequence 定义同步顺序；Cursor 定义已经处理到哪里；Base Version 定义修改基于哪个版本；Conflict Copy 保证并发修改不会静默丢失。**

---

# 74. v1.1 一句话定义

> **LightNote 是一个 Local-First 知识库系统：客户端以 SQLite 作为完整数据副本，通过 Entity Change Log 记录数据变化，通过 Sync Outbox 管理待发送变更，通过 Server Sequence 实现可靠增量同步，通过 Base Version + Conflict Preservation 处理多设备并发修改，通过内容寻址 Blob 实现附件去重和断点同步。**

---

# 75. 开发实现优先级

正式编码前必须首先实现：

```text
P0
├── SQLite Schema（含 users / devices / sync_state / blob_download_queue）
├── Migration
├── Entity Repository
├── Transaction
├── Change Log
├── Sync Outbox
└── Sync Cursor
```

然后：

```text
P1
├── Go Server
├── JWT 认证（user_id + device_id claims，Device Identity Binding）
├── Push
├── Server Commit
├── Pull（含 Pull Version Guard）
├── Idempotency
└── Version Conflict
```

然后：

```text
P2
├── Blob
├── Chunk Upload
├── Blob Lazy Download（后台队列 + SHA-256 校验 + 原子落盘）
├── FTS5
└── Device Management
```

最后：

```text
P3
├── Vue UI
├── Editor
├── Tree
├── Search
├── Conflict Center
└── Settings
```

**同步核心完成并通过异常场景矩阵后，再大规模开发 UI。**