# LightNote Tauri IPC Contract v1.1

> **状态：** Contract Freeze  
> **所有者：** Agent 0  
> **用途：** Vue UI（Agent 3）与 Rust Core（Agent 1）之间的唯一接口契约  
> **约束：** Vue Mock 的接口签名必须与本文件一致（命名空间 + 参数 + 返回值），否则视为契约违反

---

# 1. 约定

```text
命令命名：{resource}.{action}（点号命名空间）
参数/返回值：JSON（camelCase）
错误：返回 { code, message }（Tauri command 以 Result 表达，UI 层统一处理）
所有命令同步或异步均可，但返回值结构固定
```

## 1.1 分层规则（Phase 5 冻结）

```text
Vue 组件
   ↓ 只允许调用 store / api 封装层
Vue Store / Frontend Service（client/src/store、client/src/api）
   ↓ invoke（COMMAND_MAP 映射）
Tauri Command（client/app/src/main.rs）
   ↓
Rust Service 层（lightnote_core::commands::Core 门面）
   ↓
Repository（lightnote_core::repo）
   ↓
SQLite
```

**硬性规则：**

```text
1. Vue 组件禁止直接调用 Tauri invoke 或 Repository
2. 所有数据访问必须经过 store / api 封装层
3. Rust 侧禁止 UI 直接触碰 repo 表结构（一律经 Core 门面）
4. 保存（本地）与 Sync Push 完全解耦：
   UI 保存 → SQLite transaction → entity_changes + outbox → 返回
   Push 由 SyncEngine 独立调度，不由 UI 保存触发
```

这样 Tauri / Web / CLI 均可复用 Service 层（Core 门面）。

**Tauri 命令名映射（2026-08-11，Phase 4 裁定）：**

```text
Rust 函数名必须为 snake_case（Rust 标识符限制）
UI 契约保持点号命名（本文件）
client/src/api/ipc.ts 的 COMMAND_MAP 做映射：
  'notes.list'      → notes_list
  'notes.saveContent' → notes_save_content
  'search.query'    → search_query
  ...（全部命令均按 点号→下划线 映射，见 COMMAND_MAP）
```

新增真实链路入口命令（不在点号命名空间内）：

```text
auth_login(serverUrl, username, password, deviceName)
  → 调服务端 POST /api/v1/auth/login，缓存 token，构造 SyncEngine + BlobTransport
```

Vue 侧调用示例（Tauri）：

```ts
import { invoke } from '@tauri-apps/api/core'

const notes = await invoke<Note[]>('notes.list', { parentNoteId: 'root' })
```

Mock 实现必须暴露相同签名：

```ts
// src/mock/ipc.ts — Agent 3 的 MockRepository 与真实 invoke 同构
```

---

# 2. Notes

## notes.list

```text
参数: { parentNoteId?: string, includeDeleted?: boolean }
返回: NoteMeta[]（树形懒加载：未传 parentNoteId 返回根节点）
```

NoteMeta:

```json
{
  "noteId": "01J...",
  "title": "Docker 网络",
  "noteType": "text",
  "isDeleted": false,
  "sortOrder": 1,
  "version": 3
}
```

## notes.get

```text
参数: { noteId: string }
返回: Note（含 blobId；正文需再经 blobs.get 获取）
```

## notes.create

```text
参数: { parentNoteId: string, title: string, noteType?: string }
返回: NoteMeta（本地事务已产生 Change + Outbox）
```

## notes.update

```text
参数: { noteId: string, title?: string }
返回: NoteMeta
```

## notes.delete

```text
参数: { noteId: string }
返回: { ok: true }（Tombstone；同时产生 DELETE Change）
```

## notes.restore

```text
参数: { noteId: string }
返回: NoteMeta（回收站恢复，is_deleted=0）
```

---

# 3. Content（正文）

## notes.saveContent

```text
参数: { noteId: string, content: string }
返回: { blobId: string }（写入新 blob，更新 note.blob_id，同事务产生 Change）
说明: UI 侧负责 500ms~2s debounce；同步由 Sync Engine 独立调度，不由本命令触发
```

## notes.getContent

```text
参数: { noteId: string }
返回: { blobId: string, content: string | null }
说明: blob 本地缺失时 content=null，UI 显示加载占位，由 Lazy Download 补拉
```

---

# 4. Tree

## tree.children

```text
参数: { parentNoteId: string }
返回: TreeNode[]（只含未删除直接子节点，按 sortOrder 升序）
```

## tree.move

```text
参数: { branchId: string, newParentNoteId: string, newSortOrder?: number }
返回: { ok: true }（产生 branch Change）
```

---

# 5. Search（FTS5，本地派生）

## search.query

```text
参数: { query: string, limit?: number }
返回: SearchResult[]
```

SearchResult:

```json
{
  "noteId": "01J...",
  "title": "...",
  "snippet": "...",
  "matchedTags": ["java"]
}
```

---

# 6. Sync

## sync.status

```text
参数: 无
返回: {
  "state": "idle | preparing | pushing | pulling | applying | completed | error",
  "lastSyncAt": 1730000000000,
  "pendingCount": 3,
  "failedCount": 1
}
```

## sync.trigger

```text
参数: 无
返回: { started: true }
```

---

# 7. Blobs

## blobs.get

```text
参数: { blobId: string }
返回: { data: number[] | string }（base64 / 路径，Agent 1 定；UI 不直接解析二进制）
```

## blobs.exists

```text
参数: { blobId: string }
返回: { exists: boolean }
```

---

# 8. Tags / Attributes

## tags.list

```text
参数: { noteId?: string }
返回: Tag[]（noteId 省略时返回全部标签统计）
```

Tag:

```json
{
  "name": "java",
  "noteCount": 12
}
```

## tags.add

```text
参数: { noteId: string, name: string, value?: string }
返回: Attribute（产生 attribute Change）
```

## tags.remove

```text
参数: { attributeId: string }
返回: { ok: true }
```

---

# 9. Trash

## trash.list

```text
参数: 无
返回: NoteMeta[]（is_deleted=1）
```

## trash.empty

```text
参数: 无
返回: { deleted: number }（物理删除 + is_erased 语义；v1.1 可仅永久标记，经 Agent 0 确认）
```

---

# 10. Conflict Center

## conflicts.list

```text
参数: 无
返回: ConflictInfo[]
```

ConflictInfo:

```json
{
  "noteId": "01J...conflict",
  "conflictOfNoteId": "01J...original",
  "title": "Docker 网络（冲突副本）",
  "version": 4,
  "updatedAt": 1730000000000,
  "updatedBy": "device-b"
}
```

## conflicts.resolve

```text
参数: {
  "conflictNoteId": "01J...",
  "action": "keep_conflict | discard_conflict"
}
返回: { ok: true }
```

---

# 11. Settings

## settings.get

```text
参数: 无
返回: {
  "serverUrl": "https://lightnote.example.com",
  "autoSync": true,
  "syncIntervalSec": 60,
  "lastSyncStatus": "..."
}
```

## settings.update

```text
参数: { serverUrl?: string, autoSync?: boolean, syncIntervalSec?: number }
返回: Settings
```

## settings.logout

```text
参数: 无
返回: { ok: true }
```

---

# 12. Devices

## devices.list

```text
参数: 无
返回: Device[]（含 lastSeen / revokedAt；Phase 6 后真实可用）
```

## devices.revoke

```text
参数: { deviceId: string }
返回: { ok: true }
```

---

# 13. 错误对象

所有命令错误统一：

```json
{
  "code": "NOTE_NOT_FOUND",
  "message": "笔记不存在"
}
```

常用 code：

```text
NOTE_NOT_FOUND / BRANCH_NOT_FOUND / BLOB_MISSING
SYNC_ERROR / SYNC_OFFLINE
INVALID_ARGUMENT / NOT_AUTHENTICATED
DATABASE_ERROR
```

---

# 14. Mock 与真实实现切换

```text
client/src/
├── api/           # 真实 Tauri invoke 封装（Phase 5 启用）
└── mock/          # MockRepository（Phase 1-4 使用，签名同本契约）
```

切换点：`client/src/main.ts` 单行切换 import 即可，组件层不感知差异。
