# LightNote Change Protocol v1.1

> **状态：** Contract Freeze  
> **所有者：** Agent 0（Lead）  
> **变更流程：** 任何修改须提交变更说明 → Agent 0 Review → 更新版本 → 通知全部 Agent  
> **架构基线：** 《LightNote 技术架构设计 v1.1》 §6-38 / §49-52

---

# 1. 核心概念

```text
Entity          notes / branches / attributes / blobs 真实数据
Change Log      entity_changes：实体发生过什么变化（同步历史）
Outbox          sync_outbox：本设备还有哪些 Change 未发送
Cursor          sync_state.last_server_sequence：已处理到服务端哪个 Change
```

Change Log 与 Outbox **职责分离**：

```text
本地修改   → 写 entity_changes + sync_outbox
Pull 应用  → 写 entity_changes（记录历史）+ 更新 Cursor，禁止写 sync_outbox
```

> 同步环路通过「Pull 不写 Outbox + change_id 幂等 + origin_device_id」解决。
> **禁止使用 is_synced 字段**作为环路控制机制。

---

# 2. Change 结构

```json
{
  "change_id": "01J...uuidv7",
  "origin_device_id": "device-a",
  "entity_type": "note",
  "entity_id": "01J...uuidv7",
  "operation": "CREATE | UPDATE | DELETE",
  "base_version": 3,
  "version": 4,
  "content_hash": "sha256:...",
  "payload": { "...实体快照..." },
  "created_at": 1730000000000
}
```

规则：

| 字段 | 规则 |
|---|---|
| change_id | 客户端生成 UUIDv7，永久唯一，服务端幂等依据 |
| origin_device_id | 服务端以 **JWT 中的 device_id** 覆盖写入，不信任请求体 |
| entity_type | note / branch / attribute / blob |
| operation | CREATE / UPDATE / DELETE（删除 = Tombstone，is_deleted=1） |
| base_version | 客户端开始修改时读取的实体版本（乐观锁） |
| version | 本次修改后的实体版本 = base_version + 1 |
| content_hash | 实体快照内容的 SHA-256，服务端可校验 |
| payload | **实体完整快照**（非 SQL 操作），blob 只带 blob_id，正文走 Blob API |

---

# 3. Push

```http
POST /api/v1/sync/push
Authorization: Bearer <jwt>
```

请求：

```json
{
  "changes": [
    {
      "change_id": "01J...",
      "entity_type": "note",
      "entity_id": "...",
      "operation": "UPDATE",
      "base_version": 3,
      "version": 4,
      "payload": {
        "title": "Docker 网络",
        "note_type": "text",
        "blob_id": "sha256:...",
        "is_deleted": false
      }
    }
  ]
}
```

单批上限 1000 条；单请求体积上限 8MB（超出走分页批量）。

## 3.1 Push 响应

```json
{
  "results": [
    {
      "change_id": "01J...",
      "status": "APPLIED | ALREADY_APPLIED | CONFLICT | INVALID",
      "server_sequence": 10086
    }
  ]
}
```

客户端处理：

```text
APPLIED          → 从 sync_outbox 删除
ALREADY_APPLIED  → 从 sync_outbox 删除（服务端已处理过）
CONFLICT         → 冲突副本已由服务端生成；从 sync_outbox 删除
INVALID          → 记录错误，不无限重试，等待人工/升级处理
```

## 3.2 幂等

```text
网络超时 → 客户端不确定是否成功 → Outbox 保留
    ↓
重试 Push 同一 change_id
    ↓
服务端发现 change_id 已存在 → 不再修改实体 → 返回 ALREADY_APPLIED
```

## 3.3 冲突检测

```text
base_version == 服务端实体当前 version → 无冲突，正常提交
base_version != 服务端实体当前 version → CONFLICT
```

冲突策略（Last Commit Wins + Conflict Preservation）：

```text
1. 服务端当前版本保留为主版本
2. 后提交内容不丢弃 → 生成冲突副本
3. 冲突副本 = 新 note，notes.conflict_of_note_id = 原 note_id
4. 冲突副本的创建同样产生 entity_change + server_sequence，同步到所有设备
```

非 note 实体（branch / attribute / blob）冲突策略（Agent 0 裁定）：

```text
直接 Last Commit Wins（LWW），不生成冲突副本
    ↓
原因：分支/标签/元数据属于结构元数据，冲突副本语义仅对笔记内容有意义；
      note 正文冲突已由「note 冲突副本」机制保留
```

Pull 响应不含 base_version（Agent 0 裁定）：

```text
PullChange 不带 base_version 字段
客户端记录 Change 时以 version - 1 推导（仅用于本地历史记录）
Version Guard 判定只使用 version 字段，不受影响
```

---

# 4. Server Commit（Push 服务端事务）

```text
BEGIN
    ├── 幂等检查（change_id 已存在？→ 返回 ALREADY_APPLIED，不修改实体）
    ├── 加载实体，检查 base_version
    ├── 无冲突 → 应用实体
    ├── 有冲突 → 保留当前版本 + 创建冲突副本
    ├── 写入 entity_changes
    ├── 分配 server_sequence（sync_sequence 表 AUTOINCREMENT，同事务）
    └── COMMIT
```

**硬性约束（架构 v1.1 §22.1）：**

```text
1. server_sequence 必须 AUTOINCREMENT 分配，禁止 MAX(seq)+1
2. 分配与实体/Change 写入同一事务（回滚 → 产生 gap，属正常）
3. entity_changes 行永不物理删除
4. 全局单调仅单实例成立（多实例需分段序列，v1.x 不支持）
```

---

# 5. Pull

```http
GET /api/v1/sync/changes?after={last_server_sequence}&limit=500
Authorization: Bearer <jwt>
```

服务端查询：

```sql
SELECT * FROM entity_changes
WHERE server_sequence > ?
ORDER BY server_sequence
LIMIT ?;
```

响应：

```json
{
  "changes": [
    {
      "server_sequence": 10087,
      "change_id": "...",
      "origin_device_id": "...",
      "entity_type": "note",
      "entity_id": "...",
      "operation": "UPDATE",
      "version": 5,
      "payload": {}
    }
  ],
  "next_sequence": 10087,
  "has_more": false
}
```

## 5.1 Gap 处理

```text
server_sequence 允许不连续（回滚产生 gap 属正常）
    ↓
客户端不得假设序列连续
    ↓
cursor 只记录「本批次最后一条已成功应用的 server_sequence」
    ↓
是否还有下一页一律以 has_more（或存在 > cursor 的行）为准
    ↓
禁止用 after+1 推断遗漏
```

## 5.2 Cursor 更新规则

```text
Pull 成功 → 应用 Change → SQLite COMMIT → cursor = next_sequence
```

```text
禁止：先更新 cursor 再写 SQLite
原因：应用崩溃后服务端认为已同步，客户端实际没有数据
```

---

# 6. 客户端应用 Change

## 6.1 应用顺序判断（Pull Version Guard）

```text
Pull 收到 Change
    ↓
change_id 已存在？
    ├── YES → 跳过实体应用，仅记录/推进 cursor
    └── NO  → local.version > change.version？
              ├── YES → 禁止应用快照（本地数据为准），记录 change，推进 cursor
              └── NO  → 正常应用快照
```

两层防御职责不同：

```text
change_id  → 去重（同一 Change 重复送达）
version    → 数据新旧保护（旧 Change 晚于新 Change 到达 / GC 后回放）
```

## 6.2 事务

```text
BEGIN
    ├── Apply Entity
    ├── Update FTS（本地派生）
    ├── 记录 entity_changes（历史）
    ├── 更新 sync_state（cursor）
    └── COMMIT
    ❌ 不写 sync_outbox
```

## 6.3 崩溃恢复

```text
批量应用 101/102/103，应用 103 时崩溃
    ↓
事务回滚（101/102 一并回滚）
    ↓
重启后 cursor 未推进 → 重新 from=cursor 拉取
    ↓
不丢、不跳、不重复
```

---

# 7. Sync Outbox 状态机

```text
             ┌─────────┐
             │ PENDING │◄──────────┐
             └────┬────┘           │
                  │ send           │ retry / 崩溃恢复
                  ▼                │
             ┌─────────┐           │
             │ SENDING │           │
             └────┬────┘           │
                  │                │
        ┌─────────┼─────────┐      │
        ▼         ▼         ▼      │
     SUCCESS   CONFLICT    ERROR   │
        │         │         │      │
        ▼         ▼         └──────┘
      REMOVE    REMOVE
```

```text
SENDING 不允许作为永久状态：进程崩溃后，超过超时时间（默认 5 分钟）
的 SENDING 记录必须自动恢复为 PENDING。
```

---

# 8. Sync Engine 状态机

```text
              ┌──────┐
              │ IDLE │
              └──┬───┘
                 │ trigger（启动 / 定时 60s / 网络恢复 / 手动 / 本地新 Change）
                 ▼
            ┌──────────┐
            │ PREPARING │
            └────┬─────┘
                 ▼
            ┌──────────┐
            │ PUSHING  │
            └────┬─────┘
                 │
      ┌──────────┼──────────┐
      ▼          ▼          ▼
   SUCCESS    CONFLICT    ERROR
      │          │          │
      │          ▼          │
      │    （冲突副本已生成，正常同步）
      └─────┬────┘          │
            ▼               │
       ┌─────────┐          │
       │ PULLING │          │
       └────┬────┘          │
            ▼               │
       ┌──────────┐         │
       │ APPLYING │         │
       └────┬─────┘         │
            ▼               │
       ┌───────────┐        │
       │ COMPLETED │        │
       └─────┬─────┘        │
             ▼              │
           IDLE ◄───────────┘
```

顺序固定：**Push First, Pull Second**。

---

# 9. 错误分类与重试

| 错误 | 策略 |
|---|---|
| NETWORK_ERROR | 自动重试 |
| SERVER 5xx | 指数退避重试 |
| AUTH_ERROR | 刷新 Token 后重试；刷新失败 → 停止并提示重新登录 |
| CONFLICT | 服务端已处理，正常删除 Outbox |
| INVALID_DATA | 标记错误，不无限重试 |
| BLOB_ERROR | Blob 单独重试，不阻塞 Change |
| DATABASE_ERROR | 停止同步并报警 |

重试退避：

```text
1s → 2s → 4s → 8s → 16s → 30s → 60s（封顶）
```

网络恢复后立即重新触发。

---

# 10. 同步触发时机

```text
应用启动
定时器（默认 60s）
网络恢复
手动同步
本地产生新 Change（debounce 2~5s 合并）
```

---

# 11. 端到端时序

```text
Client A                       Server                     Client B
   │ 本地修改                    │                            │
   │ BEGIN                      │                            │
   │  UPDATE entity             │                            │
   │  INSERT entity_changes     │                            │
   │  INSERT sync_outbox        │                            │
   │ COMMIT                     │                            │
   │                            │                            │
   │ POST /sync/push            │                            │
   │───────────────────────────►│                            │
   │                            │ BEGIN                      │
   │                            │  幂等/版本检查              │
   │                            │  UPDATE entity             │
   │                            │  INSERT entity_changes     │
   │                            │  分配 server_sequence      │
   │                            │ COMMIT                     │
   │◄───────────────────────────│ APPLIED / seq=10086        │
   │ 删除 Outbox                │                            │
   │                            │ GET /sync/changes?after=X  │
   │                            │◄───────────────────────────│
   │                            │─────── 10086, 10087 ──────►│
   │                            │                            │ BEGIN
   │                            │                            │  Apply entity
   │                            │                            │  Update FTS
   │                            │                            │  记录 change
   │                            │                            │  cursor=10087
   │                            │                            │ COMMIT
```

---

# 12. 关键不变量（测试必须覆盖）

```text
1. 实体修改与 Change Log 同事务
2. Push 幂等：重复 change_id 不重复执行
3. Pull 幂等：change_id 去重 + cursor 不跳
4. Pull 不写 Outbox（环路防护）
5. Version Guard：local.version > change.version 时禁止应用
6. server_sequence 严格单调、回滚不复用、gap 容忍
7. 删除 = Tombstone，长期离线设备可正确收敛
8. 冲突不静默丢失（冲突副本同步到全部设备）
9. 同步失败不影响本地操作
10. Blob 与 Entity 同步解耦
```
