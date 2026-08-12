# LightNote 技术架构设计 v1.0

> **项目名称：** LightNote  
> **文档版本：** v1.0  
> **文档状态：** 架构设计  
> **设计目标：** 构建轻量、离线优先、低成本部署、支持多设备增量同步的个人知识管理系统

---

# 1. 概述

## 1.1 项目背景

LightNote 是一款面向个人用户的轻量级知识管理与笔记系统，核心目标是提供：

- Markdown 笔记
- 树形知识组织
- 标签与关系管理
- 附件及文档归档
- 全文搜索
- 多设备同步
- 离线使用
- 数据自主可控

系统不依赖云端实时连接完成核心操作。

客户端拥有完整的数据副本，即使完全离线，用户仍然可以：

- 创建笔记
- 编辑笔记
- 删除笔记
- 调整目录
- 添加标签
- 查看历史内容
- 搜索本地知识库
- 管理附件

网络恢复后，由后台同步引擎自动完成数据同步。

---

# 2. 总体架构

LightNote 采用：

> **Tauri 2 + Rust + Web UI + SQLite + Go + REST API**

架构。

```text
                         LightNote
                             │
              ┌──────────────┴──────────────┐
              │                             │
        Desktop Client                LightNote Server
              │                             │
          Tauri 2                         Go
              │                             │
      ┌───────┼────────┐          ┌─────────┼─────────┐
      │       │        │          │         │         │
     Vue     Rust    SQLite     REST API  Sync      Blob
      │       │        │          │       Engine    Service
      └───────┴────────┘          │         │         │
              │                   └─────────┼─────────┘
              │                             │
              │                          SQLite
              │                             │
              └──────────── Sync ───────────┘
```

## 2.1 核心设计原则

### 原则 1：Local First

客户端 SQLite 是完整数据副本，而不是简单缓存。

```text
User
 ↓
Local SQLite
 ↓
UI
```

网络只负责同步，不参与本地操作的完成。

---

### 原则 2：Server 也是完整副本

服务端保存完整知识库数据：

```text
Client A ─┐
Client B ─┼── Server
Client C ─┘
```

服务端不承担“唯一真相”的角色，而是作为：

- 数据同步中心
- 多设备数据交换中心
- 数据备份中心

---

### 原则 3：Change Log 驱动同步

同步不依赖简单的：

```text
updatedAt > lastSyncTime
```

而采用：

```text
Entity
 +
Change Log
 +
Server Sequence
```

实现增量同步。

---

### 原则 4：内容与实体分离

笔记、附件等大内容通过 Blob 机制管理。

```text
Entity
  │
  └── blobId
        │
        └── Blob
```

实现：

- 内容去重
- 增量传输
- 分片上传
- 断点续传
- 内容校验

---

# 3. 技术栈

| 层级 | 技术 | 说明 |
|---|---|---|
| Desktop | Tauri 2 | 跨平台桌面应用 |
| Backend | Rust | Tauri 原生能力 |
| Frontend | Vue 3 | Web UI |
| Local DB | SQLite | 本地完整数据 |
| SQLite Driver | rusqlite | Rust SQLite 访问 |
| Server | Go | 单二进制后端 |
| Server DB | SQLite | 服务端数据存储 |
| Go SQLite Driver | modernc.org/sqlite | 纯 Go |
| API | REST/HTTPS | 客户端与服务端通信 |
| Auth | JWT | 用户及设备认证 |
| Search | SQLite FTS5 | 全文搜索 |
| Blob | 文件系统 + SQLite Metadata | 附件存储 |

---

# 4. 客户端架构

```text
┌──────────────────────────────────────┐
│              Tauri 2                 │
│                                      │
│  ┌────────────────────────────────┐  │
│  │          Vue 3 UI              │  │
│  │                                │  │
│  │ Editor / Tree / Search / Tags  │  │
│  └───────────────┬────────────────┘  │
│                  │                   │
│              Tauri IPC               │
│                  │                   │
│  ┌───────────────┴────────────────┐  │
│  │            Rust                │  │
│  │                                │  │
│  │ Repository                     │  │
│  │ Sync Engine                     │  │
│  │ Blob Manager                    │  │
│  │ Search Manager                 │  │
│  │ Encryption / Auth              │  │
│  └───────────────┬────────────────┘  │
│                  │                   │
│               SQLite                │
└──────────────────┼──────────────────┘
                   │
                 HTTPS
                   │
                   ▼
             LightNote Server
```

---

# 5. 服务端架构

服务端采用 Go 单体架构。

第一阶段不拆分微服务。

```text
lightnote-server
│
├── HTTP Server
│
├── Authentication
│
├── Note Service
│
├── Branch Service
│
├── Attribute Service
│
├── Sync Service
│
├── Blob Service
│
├── Search Service
│
└── SQLite
```

采用单二进制部署：

```text
/usr/local/bin/lightnote-server
/etc/lightnote/config.yaml
/var/lib/lightnote/lightnote.db
/var/lib/lightnote/blobs/
```

---

# 6. 数据模型

LightNote 采用类似 Trilium 的实体模型。

核心实体：

```text
notes
branches
attributes
blobs
entity_changes
devices
sync_state
```

---

# 7. notes

笔记实体。

```sql
CREATE TABLE notes (
    note_id        TEXT PRIMARY KEY,
    title          TEXT NOT NULL DEFAULT '',
    note_type      TEXT NOT NULL DEFAULT 'text',
    blob_id        TEXT,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    version        INTEGER NOT NULL DEFAULT 1,
    updated_at     INTEGER NOT NULL,
    updated_by     TEXT,
    created_at     INTEGER NOT NULL
);
```

字段说明：

| 字段 | 说明 |
|---|---|
| note_id | 全局唯一 ID |
| title | 笔记标题 |
| note_type | 笔记类型 |
| blob_id | 正文 Blob |
| is_deleted | 删除标记 |
| version | 实体版本 |
| updated_at | 更新时间 |
| updated_by | 最后修改设备 |
| created_at | 创建时间 |

---

# 8. branches

用于表达笔记之间的树形组织关系。

```sql
CREATE TABLE branches (
    branch_id      TEXT PRIMARY KEY,
    parent_note_id TEXT NOT NULL,
    child_note_id  TEXT NOT NULL,
    sort_order     INTEGER NOT NULL DEFAULT 0,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    version        INTEGER NOT NULL DEFAULT 1,
    updated_at     INTEGER NOT NULL,
    updated_by     TEXT,
    created_at     INTEGER NOT NULL
);
```

设计特点：

> Note 本身与目录关系解耦。

因此一个 Note 可以出现在多个目录下。

例如：

```text
Linux
 └── Docker
       └── Docker 网络

知识库
 └── 常用技术
       └── Docker 网络
```

两个目录可以引用同一个 Note。

---

# 9. attributes

用于标签、关系及扩展属性。

```sql
CREATE TABLE attributes (
    attribute_id   TEXT PRIMARY KEY,
    note_id        TEXT NOT NULL,
    attr_type      TEXT NOT NULL,
    name           TEXT NOT NULL,
    value          TEXT,
    is_inherited   INTEGER NOT NULL DEFAULT 0,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    version        INTEGER NOT NULL DEFAULT 1,
    updated_at     INTEGER NOT NULL,
    updated_by     TEXT,
    created_at     INTEGER NOT NULL
);
```

支持：

```text
label
relation
meta
```

示例：

```text
label:
language = Java
status = active

relation:
relatedTo = note-123

meta:
author = xxx
```

---

# 10. blobs

Blob 是内容实体。

用于保存：

- Markdown 正文
- 图片
- PDF
- Office 文档
- 其他附件

```sql
CREATE TABLE blobs (
    blob_id        TEXT PRIMARY KEY,
    content_hash   TEXT NOT NULL UNIQUE,
    size           INTEGER NOT NULL,
    mime_type      TEXT,
    storage_type   TEXT NOT NULL DEFAULT 'file',
    storage_path   TEXT NOT NULL,
    created_at     INTEGER NOT NULL
);
```

Blob 与 Note 解耦。

```text
Note A ──┐
         ├── Blob SHA256=ABC
Note B ──┘
```

实现内容去重。

---

# 11. entity_changes

这是整个同步系统的核心表。

```sql
CREATE TABLE entity_changes (
    change_id       TEXT PRIMARY KEY,
    device_id       TEXT NOT NULL,
    entity_type     TEXT NOT NULL,
    entity_id       TEXT NOT NULL,
    operation       TEXT NOT NULL,
    version         INTEGER NOT NULL,
    server_sequence INTEGER,
    content_hash    TEXT,
    payload         TEXT,
    created_at      INTEGER NOT NULL
);
```

支持：

```text
CREATE
UPDATE
DELETE
```

---

# 12. serverSequence

服务端为每一条变更分配严格递增的：

```text
server_sequence
```

例如：

```text
10001
10002
10003
10004
10005
```

客户端保存：

```text
last_server_sequence
```

同步时：

```http
GET /api/sync/changes?after=10005
```

服务器返回：

```text
10006
10007
10008
```

客户端处理完成后：

```text
last_server_sequence = 10008
```

---

# 13. 为什么不使用时间戳作为同步游标

不采用：

```text
updatedAt > lastSyncTime
```

作为同步机制。

原因：

- 设备系统时间可能不一致
- 时间精度可能不足
- 多设备同时修改可能产生相同时间
- 网络延迟无法反映实际提交顺序
- 系统时间可能被调整

因此：

```text
updatedAt
```

用于业务数据。

而：

```text
serverSequence
```

用于同步顺序。

两者职责严格分离。

---

# 14. Change ID

每个客户端生成全局唯一：

```text
changeId
```

推荐使用 UUIDv7 或 ULID。

例如：

```text
01K2XXXXXXXXXXXX
```

Change ID 用于实现幂等。

客户端发送：

```text
changeId = ABC
```

服务端第一次收到：

```text
INSERT change
```

再次收到：

```text
ABC already exists
```

直接返回成功。

因此：

```text
网络超时
↓
客户端不知道是否成功
↓
重新发送
↓
服务器不会重复执行
```

---

# 15. 同步流程

## 15.1 Push

客户端：

```text
Local SQLite
    │
    ▼
entity_changes
    │
    ▼
批量读取未同步 Change
    │
    ▼
POST /api/sync/push
```

示例：

```json
{
  "changes": [
    {
      "changeId": "01ABC",
      "entityType": "note",
      "entityId": "N001",
      "operation": "UPDATE",
      "version": 3,
      "contentHash": "sha256..."
    }
  ]
}
```

---

# 16. Server Commit

服务器收到 Change 后：

```text
BEGIN TRANSACTION

1. 检查 changeId
2. 检查实体当前版本
3. 判断是否冲突
4. 更新实体
5. 写入 entity_changes
6. 分配 serverSequence

COMMIT
```

必须保证：

> 实体修改和 Change Log 写入属于同一个事务。

禁止：

```text
先修改实体
↓
之后再写 change
```

否则可能出现：

```text
数据已经改变
但同步日志丢失
```

造成永久同步不一致。

---

# 17. Pull

客户端 Push 完成后执行：

```http
GET /api/sync/changes?after={lastServerSequence}
```

服务器返回：

```json
{
  "changes": [
    {
      "serverSequence": 10001,
      "changeId": "01ABC",
      "entityType": "note",
      "entityId": "N001",
      "operation": "UPDATE"
    }
  ],
  "nextSequence": 10001,
  "hasMore": false
}
```

客户端：

```text
读取 Change
↓
应用到 SQLite
↓
事务提交
↓
更新 lastServerSequence
```

---

# 18. Cursor 更新规则

必须保证：

```text
Change 应用成功
        ↓
SQLite COMMIT
        ↓
更新 cursor
```

禁止：

```text
先更新 cursor
↓
再写 SQLite
```

否则应用崩溃后可能造成：

```text
服务器认为客户端已经同步
客户端实际上没有数据
```

---

# 19. 同步顺序

客户端默认同步周期：

```text
60 秒
```

同时支持：

```text
手动同步
应用启动同步
网络恢复同步
修改后延迟同步
```

推荐采用：

```text
Local Change
    ↓
立即写 SQLite
    ↓
后台 Sync Queue
    ↓
网络可用
    ↓
Push
    ↓
Pull
```

用户操作不等待同步完成。

---

# 20. 冲突处理

v1.0 采用：

> **版本检测 + Last Write Wins（LWW）**

作为默认冲突策略。

实体包含：

```text
version
updatedAt
updatedBy
```

正常情况下：

```text
Client A
version 10
    ↓
UPDATE
version 11
```

如果两个客户端同时基于：

```text
version 10
```

进行修改：

```text
A → version 11
B → version 11
```

服务器发现存在并发修改。

---

# 21. v1.0 冲突策略

对于普通 Metadata：

```text
Last Commit Wins
```

对于正文 Blob：

```text
Content Hash
+
版本检测
```

如果冲突：

```text
Server Version
Local Version
      │
      ▼
生成 Conflict
```

推荐保留冲突版本，而不是直接丢弃。

例如：

```text
原笔记
 ├── 当前版本
 └── 冲突副本
       └── "xxx (冲突)"
```

这样可以避免重要笔记内容被静默覆盖。

---

# 22. 删除冲突

删除必须采用 Tombstone。

例如：

```text
Note A
isDeleted = true
```

删除不是立即物理删除。

这样离线设备重新上线后：

```text
旧 Note
```

可以根据 Tombstone 判断：

```text
该实体已经被删除
```

而不是重新创建。

---

# 23. Tombstone 生命周期

v1.0 默认：

> Tombstone 永久保留。

原因：

- 个人知识库数据量通常较小
- 删除记录数量有限
- 永久保留实现简单
- 避免长期离线设备产生脏数据

未来可以增加：

```text
Tombstone GC
```

但必须建立：

```text
所有设备同步水位
```

之后才能安全清理。

---

# 24. Blob 同步

Blob 不直接通过 JSON 传输。

采用：

```text
Metadata API
+
Blob API
```

---

## 24.1 Blob 上传

```text
POST /api/blobs/init
```

提交：

```json
{
  "hash": "sha256...",
  "size": 10485760,
  "mimeType": "application/pdf"
}
```

如果服务器已经存在：

```text
hash = sha256...
```

直接：

```text
Already Exists
```

无需上传。

---

# 25. 分片上传

大文件采用 Chunk。

例如：

```text
Blob
 │
 ├── Chunk 0
 ├── Chunk 1
 ├── Chunk 2
 ├── Chunk 3
 └── Chunk N
```

API：

```http
PUT /api/blobs/{blobId}/chunks/{index}
```

最后：

```http
POST /api/blobs/{blobId}/complete
```

服务器重新计算 SHA-256：

```text
Client Hash
      │
      ▼
Server Hash
      │
      ├── 一致 → 成功
      └── 不一致 → 失败
```

---

# 26. Blob 存储

数据库只保存 Metadata。

实际内容保存：

```text
/var/lib/lightnote/blobs/
```

推荐采用 Hash 分层目录：

```text
blobs/
 ├── ab/
 │    └── cd/
 │         └── abcdef....
 ├── ef/
 │    └── 12/
 │         └── ef1234....
```

避免单目录文件数量过大。

---

# 27. 全文搜索

不使用：

```sql
LIKE '%keyword%'
```

作为正式搜索方案。

使用：

> SQLite FTS5

建立：

```text
note_fts
```

包含：

```text
title
content
tags
```

逻辑：

```text
notes
  │
  └── blob
        │
        ▼
      FTS5
        │
        ▼
      Search
```

支持：

- 关键词搜索
- 标题搜索
- 正文搜索
- 标签搜索
- 搜索结果排序

---

# 28. REST API

基础路径：

```text
/api
```

## Authentication

```text
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
```

---

## Notes

```text
GET    /api/notes/{id}
POST   /api/notes
PUT    /api/notes/{id}
DELETE /api/notes/{id}
```

---

## Branches

```text
GET    /api/branches
POST   /api/branches
PUT    /api/branches/{id}
DELETE /api/branches/{id}
```

---

## Attributes

```text
GET    /api/notes/{id}/attributes
POST   /api/attributes
PUT    /api/attributes/{id}
DELETE /api/attributes/{id}
```

---

## Sync

```text
POST /api/sync/push
GET  /api/sync/changes
POST /api/sync/ack
```

---

## Blob

```text
POST /api/blobs/init
PUT  /api/blobs/{id}/chunks/{index}
POST /api/blobs/{id}/complete
GET  /api/blobs/{id}
GET  /api/blobs/{id}/chunks/{index}
```

---

# 29. 认证与设备

每个客户端拥有：

```text
deviceId
deviceName
deviceType
lastSeen
```

例如：

```text
PC-Windows
MacBook
Laptop
```

Token：

```text
Access Token
+
Refresh Token
```

Access Token：

```text
短生命周期
```

Refresh Token：

```text
长期有效
```

服务端支持：

```text
查看设备
撤销设备
```

---

# 30. 数据一致性

LightNote 不追求强一致性，而采用：

> **最终一致性**

允许：

```text
Client A
    │
    │ 离线修改
    ▼
Local State A

Client B
    │
    │ 离线修改
    ▼
Local State B
```

网络恢复后：

```text
A ─┐
   ├── Server ── Merge
B ─┘
```

最终所有正常在线设备：

```text
State A
State B
State C
   ↓
一致
```

---

# 31. 本地事务

每次用户操作必须保证：

```text
实体修改
+
Change Log
```

在同一个 SQLite Transaction 中完成。

例如创建 Note：

```text
BEGIN

INSERT notes

INSERT branches

INSERT entity_changes

COMMIT
```

这样不会出现：

```text
Note 已创建
但没有同步日志
```

---

# 32. 数据迁移

数据库必须维护：

```text
schema_version
```

例如：

```text
v1
v2
v3
```

客户端启动：

```text
读取 schema_version
        ↓
执行 migration
        ↓
更新版本
```

Migration 必须：

- 可重复执行
- 有明确版本号
- 按顺序执行
- 失败可恢复

禁止直接修改历史 migration。

---

# 33. 数据备份

服务端至少提供：

```text
SQLite DB
+
Blob Directory
```

备份必须保持二者一致。

推荐：

```text
SQLite Backup
+
Blob Snapshot
```

长期可以增加：

```text
ZIP Backup
```

格式：

```text
lightnote-backup/
├── manifest.json
├── database.sqlite
└── blobs/
```

---

# 34. 安全设计

服务端必须使用：

```text
HTTPS
```

敏感配置：

```text
JWT Secret
Database Path
Blob Path
```

不得写死在代码中。

建议通过：

```text
环境变量
+
config.yaml
```

配置。

---

# 35. 数据加密

v1.0：

```text
传输：
HTTPS

服务端：
普通 SQLite

客户端：
本地 SQLite
```

暂不强制数据库级加密。

未来可以增加：

```text
客户端数据库加密
Blob 加密
端到端加密
```

但端到端加密会显著增加：

- 多设备密钥同步
- 搜索实现难度
- 冲突处理复杂度
- 数据恢复难度

因此不纳入 v1.0。

---

# 36. 日志

客户端记录：

```text
Sync Start
Sync Push
Sync Pull
Sync Error
Blob Upload
Blob Download
Database Error
```

服务端记录：

```text
HTTP Request
Authentication
Sync
Blob
Database
Error
```

日志不得记录：

```text
JWT
密码
完整笔记正文
Blob 内容
```

---

# 37. 错误恢复

同步失败不得影响本地操作。

例如：

```text
用户修改 Note
        ↓
SQLite 成功
        ↓
同步失败
        ↓
Note 仍然可正常使用
```

Change 保留：

```text
entity_changes
```

下一次继续同步。

因此：

> 同步失败 ≠ 数据操作失败。

---

# 38. 网络状态

同步引擎根据网络状态自动工作：

```text
Offline
   ↓
等待
   ↓
Online
   ↓
Push
   ↓
Pull
```

支持：

```text
网络恢复自动同步
```

---

# 39. 同步状态

UI 可以展示：

```text
✓ 已同步

↻ 正在同步

⚠ 同步失败

○ 离线
```

同时显示：

```text
最后同步时间
```

但不能阻塞编辑器。

---

# 40. 性能目标

v1.0 目标：

| 指标 | 目标 |
|---|---:|
| 本地打开数据库 | < 500ms |
| 创建笔记 | < 50ms |
| 编辑保存 | < 50ms |
| 本地搜索 | < 200ms |
| 10000 Notes | 正常 |
| 100000 Notes | 正常 |
| 单次同步 Change | ≥ 1000 |
| Blob 分片 | 4~16MB |
| 服务端内存 | 目标 < 100MB |
| 典型 VPS | 1C / 1GB 可运行 |

---

# 41. 典型部署

最低配置：

```text
1 vCPU
1 GB RAM
20 GB SSD
Linux
```

部署：

```text
Internet
   │
 HTTPS
   │
 Nginx / Caddy
   │
   ▼
lightnote-server
   │
   ├── lightnote.db
   │
   └── blobs/
```

v1.0 不依赖：

```text
Redis
PostgreSQL
MySQL
Kafka
RabbitMQ
Object Storage
Kubernetes
```

---

# 42. 为什么使用 SQLite

LightNote 是典型的：

> 低并发、强本地性、小规模数据、单用户/少量用户系统。

SQLite 能够提供：

- ACID
- WAL
- FTS5
- 单文件数据库
- 极低运维成本
- 高可靠性

因此 v1.0 不引入传统数据库。

---

# 43. 为什么服务端不使用 PostgreSQL

不是 PostgreSQL 不好，而是当前阶段没有必要。

如果使用 PostgreSQL：

```text
LightNote
 ├── Go
 ├── PostgreSQL
 └── Blob Storage
```

部署复杂度明显增加。

当前目标：

```text
Single Binary
+
Single SQLite
+
Blob Directory
```

更符合 LightNote 的产品定位。

未来如果出现：

- 多用户
- 高并发
- 团队协作
- SaaS
- 百万级同步用户

再考虑：

```text
PostgreSQL
Object Storage
Redis
```

---

# 44. v1.0 不做的事情

为了控制项目复杂度，以下功能暂不纳入 v1.0：

- 多人实时协作
- 在线多人编辑
- E2E 加密
- Web 在线编辑器
- 移动端
- AI 自动整理
- AI 总结
- OCR
- 多租户 SaaS
- PostgreSQL
- Redis
- 分布式部署
- Kubernetes
- WebSocket 实时同步

---

# 45. v1.0 核心功能范围

第一阶段必须完成：

```text
┌─────────────────────────────┐
│          LightNote          │
├─────────────────────────────┤
│                             │
│ Markdown 编辑               │
│ Markdown 预览               │
│ 树形目录                    │
│ 多父节点                    │
│ 标签                        │
│ 笔记关系                    │
│ 附件                        │
│ Blob 去重                   │
│ 回收站                      │
│ 全文搜索                    │
│ 离线使用                    │
│ 自动同步                    │
│ 手动同步                    │
│ 多设备                      │
│ 冲突处理                    │
│ 数据导入/导出               │
│ 服务端备份                  │
│                             │
└─────────────────────────────┘
```

---

# 46. 推荐开发顺序

不要按照 UI → API → 同步的方式开发。

推荐：

```text
Phase 1
数据模型
    ↓
SQLite Schema
    ↓
Migration
```

```text
Phase 2
Repository
    ↓
Note
Branch
Attribute
Blob
```

```text
Phase 3
Change Log
    ↓
Local Transaction
    ↓
Sync Engine
```

```text
Phase 4
Go Server
    ↓
REST API
    ↓
Server SQLite
```

```text
Phase 5
Push / Pull
    ↓
Conflict
    ↓
Blob Sync
```

```text
Phase 6
Vue UI
    ↓
Editor
    ↓
Tree
    ↓
Search
```

```text
Phase 7
完整测试
    ↓
多设备测试
    ↓
离线测试
    ↓
异常恢复测试
```

---

# 47. 同步测试矩阵

同步模块必须重点测试：

| 场景 | 预期 |
|---|---|
| 单设备修改 | 正常同步 |
| 双设备修改不同 Note | 自动合并 |
| 双设备修改同一 Note | 产生冲突 |
| 删除后离线 | 上线后正确删除 |
| 删除 vs 修改 | 按冲突规则处理 |
| Push 网络中断 | 自动重试 |
| Pull 网络中断 | 下次继续 |
| 重复 Push | 幂等 |
| 重复 Pull | 不产生重复数据 |
| 客户端崩溃 | 不丢 Change |
| 服务端崩溃 | 事务回滚 |
| Blob 上传中断 | 支持续传 |
| Blob Hash 不一致 | 拒绝提交 |
| 长时间离线 | 重新同步成功 |
| 系统时间错误 | 不影响同步游标 |

---

# 48. 核心架构结论

LightNote v1.0 最终采用：

```text
Tauri 2
    +
Vue 3
    +
Rust
    +
SQLite
    +
Go
    +
REST
    +
JWT
    +
FTS5
    +
Change Log
    +
Server Sequence
    +
Blob
```

最终形成：

```text
                ┌───────────────┐
                │   LightNote   │
                └───────┬───────┘
                        │
          ┌─────────────┴─────────────┐
          │                           │
       Desktop                      Server
          │                           │
       SQLite                       SQLite
          │                           │
     Change Log                Change Log
          │                           │
          └────────── Sync ───────────┘
                        │
                     Blob
                        │
                   File Storage
```

核心思想可以概括为：

> **本地优先、数据同构、Change Log 同步、Server Sequence 定序、Blob 独立存储、最终一致性。**

---

# 49. 架构演进路线

## v1.0

```text
单用户
SQLite
REST
多设备同步
Blob
FTS5
```

## v1.5

```text
冲突中心
版本历史
数据导入导出
自动备份
设备管理
```

## v2.0

```text
端到端加密
移动端
Web Client
高级搜索
```

## v3.0

如果未来产品化：

```text
PostgreSQL
Object Storage
Redis
多租户
团队协作
SaaS
```

---

# 50. 最终设计原则

LightNote 的架构设计遵循以下原则：

1. **本地操作永远不依赖网络**
2. **SQLite 是完整数据副本，而不是缓存**
3. **所有数据修改必须产生 Change Log**
4. **实体修改与 Change Log 必须处于同一事务**
5. **Change ID 保证同步幂等**
6. **Server Sequence 保证同步顺序**
7. **删除采用 Tombstone**
8. **Blob 与实体元数据分离**
9. **Blob 使用 Hash 去重**
10. **全文搜索使用 FTS5**
11. **同步失败不能影响本地数据**
12. **默认采用最终一致性**
13. **v1.0 优先简单可靠，不提前引入分布式组件**
14. **所有同步机制必须能够在异常、断网、重复请求和客户端崩溃情况下恢复**

---

# 附录 A：核心数据关系

```text
                     ┌─────────────┐
                     │    notes    │
                     └──────┬──────┘
                            │
             ┌──────────────┼──────────────┐
             │              │              │
             ▼              ▼              ▼
         branches       attributes       blobs
             │
             │
             ▼
          notes


notes / branches / attributes / blobs
                  │
                  ▼
           entity_changes
                  │
                  ▼
            sync engine
```

---

# 附录 B：一次完整同步

```text
Client A

用户修改 Note
     │
     ▼
SQLite Transaction
     │
     ├── UPDATE notes
     │
     └── INSERT entity_changes
             │
             ▼
        Sync Queue
             │
             ▼
       POST /sync/push
             │
             ▼
          Server
             │
      ┌──────┴──────┐
      │             │
   幂等检查       版本检查
      │             │
      └──────┬──────┘
             │
             ▼
       SQLite Transaction
             │
       ├── UPDATE entity
       └── INSERT change
             │
             ▼
       serverSequence=10086
             │
             ▼
          Client A
             │
             ▼
      GET /sync/changes
             │
             ▼
          Client B
             │
             ▼
        Apply Change
             │
             ▼
        SQLite Commit
             │
             ▼
      cursor = 10086
```

---

# 附录 C：一句话架构定义

> **LightNote 是一个基于 Tauri + SQLite 的 Local-First 知识库客户端，以 Go + SQLite 作为轻量同步服务端，通过 Change Log、Server Sequence 和 Blob Hash 实现可靠的多设备增量同步与内容去重。**