# LightNote GUI 延迟验收协议（GUI-001 ~ GUI-008）+ 双设备 E2E 执行包

> **状态：** Deferred Acceptance（延迟验收）  
> **执行环境：** 带显示器的 Windows 真机（当前开发环境为无头环境，**无法运行 Tauri GUI**）  
> **目的：** 仅验证「真实用户操作链路」在 Tauri 桌面壳 + Vue UI 下的端到端可用性。

## 0. 适用范围与分层原则（先读）

- **自动化测试已证明正确性**：Rust Core 单元 28 / Vertical Slice 9 / Hardening 16（含 Phase 7 异常矩阵 8 + Phase 8.4 `fts_scaling`）/ Smoke 10 / Go Server 4 包 / Phase 8 FTS 100K Gate 全绿（见 `docs/CHANGELOG.md`）。
- **本协议不复测 DB / 同步算法一致性**，仅验证：登录 → UI 交互 → IPC → 同步在真实桌面环境下的用户可见行为。
- 凡源码中无法确认的 UI 细节，标注 **「需在真机确认」**，不得臆造。

---

## 1. 环境要求

| 项 | 要求 |
|---|---|
| 操作系统 | Windows 10/11，带显示器与键鼠 |
| 服务端 | 一个 Go 服务端（`server/cmd/lightnote-server`），两客户端共享 |
| 客户端 | **两个独立 Tauri 客户端实例**（命名 **PC-A** / **PC-B**） |
| 网络 | 三者可经 `http://<server-ip>:8080` 互访（同机或跨机均可） |
| 工具 | `curl`（种子数据）、WebView2 DevTools（F12）、文本编辑器 |

### 1.1 关键约束（源自源码，务必遵守）

1. **真实 IPC 开关**：`client/src/api/ipc.ts` 中 `USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false'`。**必须设置 `VITE_USE_MOCK=false`**，否则前端走 Mock，不会调用 Tauri 命令。
2. **同步为手动触发**：`client/app/src/main.rs` 无后台自动同步线程（`auto_sync`/`sync_interval_sec` 仅存于内存设置，未驱动定时器）。所有 push/pull 均由用户点击 **「立即同步」**（SyncStatusBar 弹层或命令面板）触发；登录后 `App.vue::onLoggedIn` 会自动触发一次 `sync.trigger`。
3. **双实例数据隔离**：`main.rs::setup` 用 `app.path().app_data_dir()` 固定为 `%APPDATA%\com.lightnote.app\`（identifier 见 `client/app/tauri.conf.json`）。同 Windows 用户下开两个进程 **共享同一份 `lightnote.db`** → 不能作为两个独立设备。
   - **单机双设备方案**：使用**两个 Windows 用户账户**分别登录运行客户端（各账户 `%APPDATA%` 独立）。
   - **或**：使用**两台物理机**。
   - 未启用 `tauri-plugin-single-instance`（`client/app/Cargo.toml` 无此依赖），故多窗口可启动，但数据目录仍是隔离的关键。

---

## 2. 前置准备 / 环境搭建

### 2.1 启动 Go 服务端

源码：`server/cmd/lightnote-server/main.go`；配置加载：`server/internal/config/config.go`。

```bash
# 在 server/ 目录下
export LIGHTNOTE_ADDR=":8080"
export LIGHTNOTE_DB_PATH="./run/server.db"
export LIGHTNOTE_BLOB_DIR="./run/blobs"
export LIGHTNOTE_JWT_SECRET="acceptance-secret-change-me"   # 不配则每次启动随机生成，已签发 Token 失效
export LIGHTNOTE_USERNAME="admin"
export LIGHTNOTE_PASSWORD="admin123"
export LIGHTNOTE_TOKEN_TTL_HOURS="2"
go run ./cmd/lightnote-server > server.log 2>&1 &
```

- 默认账号：`admin` / `admin123`（`config.example.yaml`）。
- 健康检查：`curl http://localhost:8080/healthz` → `{"status":"ok"}`（`docs/api.md` §6）。
- 日志：服务端 `log.Printf` 输出到 stdout/stderr，已重定向至 `server.log`。

### 2.2 构建 / 运行 Tauri 客户端

源码：`client/app/`（Rust 壳）+ `client/`（Vue）。`client/app/tauri.conf.json`：`beforeDevCommand: npm --prefix ../ run dev`，`devUrl: http://localhost:5173`。

```powershell
# 关键：禁用 Mock
$env:VITE_USE_MOCK = "false"

# 开发模式（带热重载 + DevTools）
cd client\app
cargo tauri dev

# 或生产构建后运行
cargo tauri build
# 产物在 client\app\target\release\，双击 LightNote.exe
```

- 两个客户端实例的「设备名」分别填 **PC-A** / **PC-B**（见 GUI-001）。
- 客户端数据目录：`%APPDATA%\com.lightnote.app\`（含 `lightnote.db`、`blobs\`）。

### 2.3 默认凭据

| 项 | 值 |
|---|---|
| 服务端地址 | `http://<server-ip>:8080` |
| 用户名 | `admin` |
| 密码 | `admin123` |

---

## 3. 用例 GUI-001 ~ GUI-008

### GUI-001 登录

**前置条件**：服务端已启动且 `/healthz` 正常；客户端为首次启动（数据目录无 `server_url` 记录，`App.vue::onMounted` 检测到 `settings.serverUrl` 为空 → 显示 `LoginView`）。

**操作步骤**（UI 元素见 `client/src/components/LoginView.vue`）：
1. 启动客户端，进入登录页（标题 `LightNote`）。
2. 「服务端地址」填 `http://<server-ip>:8080`。
3. 「用户名」保留 `admin`（默认值）。
4. 「密码」填 `admin123`。
5. 「设备名」填 `PC-A`（PC-B 实例填 `PC-B`）。
6. 点击「登录」按钮（加载中显示「登录中…」）。

底层：`authLogin()` → Tauri 命令 `auth_login` → `POST /api/v1/auth/login`（`main.rs:85`、`docs/api.md` §1.5）。

**预期结果（PASS）**：
- 登录页消失，进入主视图：左侧栏出现「目录 / 标签 / 搜索 / 回收站」面板切换；中间「笔记列表」；底部状态栏显示 `SyncStatusBar`。
- 登录后自动触发一次同步（`App.vue::onLoggedIn` 调用 `sync.trigger`），状态栏显示 `✓ 已同步` 或同步中态。
- access_token **不在任何 UI 元素中明文展示**（`main.rs::auth_login` 仅存入内存 `state.token`，无渲染处）。

**失败判定（FAIL）**：
- 出现红色错误提示「登录失败：…」（`LoginView.vue` `.error`）且停留登录页。
- 服务端 `server.log` 出现非 200 / panic。

**截图要求**：登录页（填好未点）+ 登录成功后主视图（含状态栏）。

**日志采集**：
- 客户端：DevTools Console（F12，开发模式）；`auth_login` 失败时前端 `error.value` 文案。
- 服务端：`server.log` 中 `/api/v1/auth/login` 行。
- 备注：Phase 9.2a 起 `auth_login` 同时处理 `access_token`（内存）/`refresh_token`（TokenStore，0600 凭据文件）/`device_id`（session.json），全部持久化（除 access_token 仅内存）。登录链路在真机确认。

---

### GUI-002 A 创建 → B Pull

**前置条件**：GUI-001 通过；PC-A、PC-B 均已登录同一服务端、同一用户。

**操作步骤**（`TreeView.vue` / `NoteList.vue` / `EditorPane.vue` / `store/notes.ts`）：
1. PC-A：左侧「目录」面板，点击「知识库」根节点；点击树工具栏「＋ 新建笔记」（或笔记列表「＋ 新建」、或 `Ctrl+N`）。
2. 新笔记「未命名」自动在 `EditorPane` 打开（`store.createAndOpen`）。
3. 顶部「标题」输入框改名；正文 `textarea`（编辑模式）输入内容。
4. 停顿 > 0.8s 触发 debounce 自动保存（`store.updateContent` → `notes.saveContent` → Tauri `notes_save_content`）。
5. 点击底部状态栏「同步状态」→ 弹层「立即同步」（`SyncStatusBar.vue::onTrigger` → `sync.trigger`）。
6. PC-B：同样点击「立即同步」。

底层 IPC：`notes.create` / `notes.saveContent` / `sync.trigger`（`docs/ipc.md` §2-3、§6）。

**预期结果（PASS）**：PC-B 同步完成后，笔记列表 / 目录树中出现 PC-A 创建的同名笔记，打开后正文一致。

**失败判定（FAIL）**：PC-B 同步后未见该笔记；或状态栏显示「⚠ 同步失败」。

**截图要求**：PC-A 编辑+同步成功态；PC-B 同步后看到该笔记。

**日志采集**：两端 Console；`server.log` 的 `/sync/push`（A）与 `/sync/changes`（B）记录。

---

### GUI-003 B 修改 → A Pull

**前置条件**：GUI-002 已完成，该笔记在两端可见。

**操作步骤**：
1. PC-B：打开该笔记，修改正文，等待 debounce 自动保存（或 `Ctrl+S` 强制保存 `store.saveNow`）。
2. PC-B：点击「立即同步」。
3. PC-A：点击「立即同步」，重新打开/切回该笔记。

**预期结果（PASS）**：PC-A 看到与 PC-B 一致的最新正文（`version` 递增，乐观锁 `base_version+1`）。

**失败判定（FAIL）**：PC-A 正文未更新或回退；状态栏报错。

**截图要求**：B 修改后 + A pull 后两屏对比。

**日志采集**：同 GUI-002；关注 `/sync/push` 响应是否含 `CONFLICT`。

---

### GUI-004 A 删除 → B Pull

**前置条件**：存在一篇可删除笔记。

**操作步骤**（`NoteList.vue::onDelete`）：
1. PC-A：在「笔记列表」中悬停目标笔记，点击出现的「🗑」按钮，在确认框「删除「<title>」？」中确认（`store.deleteNote` → `notes.delete` → Tauri `notes_delete`，产生 Tombstone + DELETE Change）。
2. PC-A：点击「立即同步」。
3. PC-B：点击「立即同步」。

**预期结果（PASS）**：PC-B 同步后，该笔记从**活跃笔记列表**消失（`is_deleted=1` 被 `notes.list` 默认排除）。

**失败判定（FAIL）**：PC-B 仍能在活跃列表看到该笔记。

**截图要求**：A 删除确认 + 同步；B 同步后列表（无该笔记）。

**日志采集**：两端 Console；服务端 `/sync/push` DELETE 条目。

> 补充：被删笔记应在回收站可见（`TrashPanel.vue` / `trash.list`）——其是否同步到 B 的回收站 **需在真机确认**（Tombstone 是否进入对端 `trash_list` 取决于 core 应用逻辑）。

---

### GUI-005 冲突（并发双写）

**前置条件**：GUI-002 通过；理解乐观锁 `base_version`（`docs/change-protocol.md` §2）。

**操作步骤**（利用手动同步制造并发）：
1. PC-A 创建笔记「冲突测试」，输入初始正文，保存并「立即同步」（服务端到达 version N）。
2. PC-B「立即同步」拉到该笔记（base_version=N）。
3. PC-A 修改正文并保存（本地 base_version=N→N+1 待发），**先不同步**。
4. PC-B 修改同一笔记正文并保存，**PC-B 先「立即同步」**（服务端 N→N+1 接受）。
5. PC-A 再「立即同步」：A 的变更 base_version=N，而服务端已到 N+1 → 返回 `VERSION_CONFLICT`（HTTP 409，`docs/api.md` §7），按 Conflict Preservation 生成冲突副本。

底层：`conflicts.list` IPC 与 Tauri `conflicts_list` 已注册（`main.rs:244`、`contract.ts`），冲突副本 `ConflictInfo.title` 形如「冲突测试（冲突副本）」（`docs/ipc.md` §10）。

**预期结果（PASS）**：PC-A 同步后，目录树/笔记列表中出现「冲突测试（冲突副本）」副本，原笔记为服务端版本；无数据丢失。

**失败判定（FAIL）**：任一端数据丢失；或无冲突副本生成且报错中断。

**截图要求**：冲突副本出现后的列表；两端同步状态。

**日志采集**：`server.log` 中 409 / `VERSION_CONFLICT` 记录；两端 Console。

> 注：当前 Vue 组件中**无独立「冲突中心」面板**（组件清单无 `ConflictPanel.vue`），冲突副本以普通笔记形式出现在树/列表中。是否有专属高亮 **需在真机确认**。

---

### GUI-006 附件 / Blob

**前置条件**：存在一篇笔记用于挂附件。

**操作步骤**（`EditorPane.vue` 附件区）：
1. PC-A：打开笔记，编辑器底部「附件」区点击「＋ 添加」选择文件，或将文件**拖入**附件区（`.attachments.drag-over` 高亮）。
2. `store.attachFile` → `notes.attach` → Tauri `notes_attach` → `core.attach_bytes`（写入内容寻址 blob）。
3. 附件列表出现「📎 <文件名>」条目（`attachmentLabel` 去除前缀）。
4. PC-A：保存正文，「立即同步」。
5. PC-B：「立即同步」，打开该笔记。

底层：Blob 走 `/blobs/init` / `/blobs/{id}/chunks/{index}` / `/blobs/{id}/complete` / `/blobs/{id}`（`docs/api.md` §4）；懒下载见 `docs/ipc.md` §3 `notes.getContent`（blob 缺失返回 `content=null`）。

**预期结果（PASS）**：PC-B 附件区显示相同的「📎 <文件名>」；正文可打开。

**失败判定（FAIL）**：PC-B 附件缺失或打开失败；blob 拉取报错。

**截图要求**：A 添加附件 + 同步；B 同步后附件区。

**日志采集**：两端 Console；`server.log` 的 `/blobs/*` 记录。

> 注：blob 懒下载在 Vue 侧未见显式触发 `blobs.get`（`store.openNote` 仅 `notes.getContent`，`content=null` 时显示空占位）。**懒下载队列的实际触发与占位回填行为 需在真机确认**。

---

### GUI-007 离线恢复

**前置条件**：PC-A 已登录且曾有成功同步。

**操作步骤**：
1. 断开服务端：停止 Go 服务端进程（或切断网络）。
2. PC-A：创建/修改一篇笔记，等待 debounce 本地保存（`notes.saveContent` 写本地事务 → `entity_changes` + `sync_outbox`，`docs/ipc.md` §1.1 分层规则 4）。
3. PC-A：点击「立即同步」→ 应失败，状态栏显示「⚠ 同步失败」或「○ 离线」（`SyncStatusBar.vue::labels`）。
4. 恢复服务端：重新启动 Go 服务端（或恢复网络）。
5. PC-A：再次「立即同步」→ outbox 中积压 Change 被推送。
6. PC-B：「立即同步」验证收到离线期间的变更。

**预期结果（PASS）**：重连后同步成功，离线期间的本地修改完整同步到服务端与 PC-B；cursor 正确推进，无重复/丢失。

**失败判定（FAIL）**：重连后 outbox 未推；或同一变更重复应用。

**截图要求**：离线态状态栏；重连后「✓ 已同步」态；PC-B 收到变更。

**日志采集**：`server.log`（断连期间应无请求，重连后现 `/sync/push`）；客户端 Console。对照 `tests/hardening/examples/recover_offline.rs` 6 项校验（自动化已覆盖逻辑，此处仅看真机表现）。

---

### GUI-008 重启恢复

**前置条件**：PC-A 已有数据（若干笔记），曾成功同步。

**操作步骤**：
1. 完全关闭 PC-A 客户端窗口（确保进程退出）。
2. 重新启动客户端。

**预期结果（PASS）**：
- 本地数据持久：重启后笔记树 / 笔记内容仍在（SQLite 位于 `%APPDATA%\com.lightnote.app\lightnote.db`）。
- 同步 cursor 持久：重启后再次同步只拉增量，不重拉历史（`sync_state.last_server_sequence` 持久化，已被 Hardening 覆盖）。

**失败判定（FAIL）**：重启后笔记丢失；或同步从头全量重拉。

**截图要求**：关闭前列表；重启后列表（一致）。

**日志采集**：两次启动的 Console；服务端 `/sync/changes?after=` 的 `after` 值（应为上次 cursor，非 0）。

> 关键说明（Phase 9.2a 已接线）：`auth_login` 现持久化 `refresh_token`（TokenStore，0600 凭据文件）+ `server_url`/`device_id`/`device_name`（session.json）；`access_token` 仅内存 + 记 `expires_in`。启动时 `App.vue::onMounted` 调 `auth_status` → 若有会话则 `auth_refresh`（用 refresh_token 换新 access_token + 轮换存新 refresh_token）→ 进主界面；失败（401/403）→ 回登录页。运行期 `sync.trigger`/`devices.*` 前置 `ensure_valid_token`，access_token 过期自动 refresh。`settings.logout` 清空全部会话。GUI-008 现已具备验收条件（真机确认 refresh 链路 + 2h 过期自动续）。

---

## 4. 双设备 E2E 执行包

### 4.1 双设备启动方式

| 方案 | 做法 | 数据隔离 |
|---|---|---|
| 单机双账户（推荐） | Windows 账户 U1 运行 PC-A、账户 U2 运行 PC-B，均 `VITE_USE_MOCK=false` | 各自 `%APPDATA%\com.lightnote.app\` 独立 ✅ |
| 双机 | 两台 Windows 真机，分别运行一个客户端 | 天然隔离 ✅ |
| 单机单账户双窗口 | 同账户起两个进程 | ❌ 共享同一 `lightnote.db`，**不能**作为双设备 |

> Tauri 无实例 id / 数据目录覆盖参数（`main.rs::setup` 写死 `app_data_dir()`，client_id/device_id 由 `std::process::id()` 派生）。如需单账户双实例隔离，**须改代码**（超出本协议范围，此处不修改）。

### 4.2 服务端配置（两客户端共享）

```bash
LIGHTNOTE_ADDR="0.0.0.0:8080"        # 监听所有网卡，便于跨机
LIGHTNOTE_DB_PATH="./run/server.db"
LIGHTNOTE_BLOB_DIR="./run/blobs"
LIGHTNOTE_JWT_SECRET="<固定值，勿随机>"
LIGHTNOTE_USERNAME="admin"
LIGHTNOTE_PASSWORD="admin123"
```

两客户端「服务端地址」均填 `http://<server-ip>:8080`。

### 4.3 测试数据种子

自动化 fixture（`tests/perf/`、`tests/hardening/`、`tests/smoke/`、`tests/vertical-slice/`）均为无头 Rust 直调 core，**不经过 GUI**。GUI 用例建议**直接用 UI 手工建笔记**；如需预置，可用 curl 经服务端 API 注入（参考 `docs/api.md` §1.5、`docs/change-protocol.md` §3）：

```bash
# 1) 登录拿 access_token（device_name 区分种子设备）
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123","device_name":"seed","device_type":"desktop"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

# 2) Push 一条 note 变更（结构见 change-protocol.md §2-3）
curl -X POST http://localhost:8080/api/v1/sync/push \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"changes":[{ \
    "change_id":"01JSEED000000000000000000", \
    "entity_type":"note","entity_id":"01JSEEDNOTE00000000000", \
    "operation":"CREATE","base_version":0,"version":1, \
    "payload":{"title":"种子笔记","note_type":"text","is_deleted":false}}]}'
```

> 上述为最小可用结构；完整字段（`origin_device_id` 由服务端按 JWT 覆盖、`content_hash`、blob 正文走 Blob API）以 `docs/change-protocol.md` 为准。客户端随后「立即同步」即可拉到种子。

### 4.4 日志采集

| 来源 | 位置 / 方式 |
|---|---|
| 服务端 stdout/stderr | 启动时重定向 `> server.log 2>&1`（`log.Printf` 输出） |
| 客户端开发模式 | `cargo tauri dev` 终端 stdout/stderr + WebView2 DevTools（F12）Console |
| 客户端生产模式 | 无 `tauri-plugin-log` 依赖（`client/app/Cargo.toml` 未列出），**默认无日志文件**；如需日志请在开发模式下执行或启用 DevTools（release 下 DevTools 是否可用 **需在真机确认**） |

### 4.5 清理脚本（每轮之间重置）

```powershell
# 客户端（每个 Windows 账户各清一次）
Remove-Item -Recurse -Force "$env:APPDATA\com.lightnote.app"   # 含 lightnote.db、blobs\

# 服务端
Stop-Process -Name lightnote-server -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force server\run        # 含 *.db、blobs\
```

被清理的路径均为 `.gitignore` 已忽略的运行时数据（`*.db`、`*.sqlite*`、`.lightnote/`、`*/.lightnote/`、`*.log`）。

---

## 5. 分层原则（再次强调）

- 本协议 = **真机用户操作链路** 验收，**不是** DB / 同步正确性复测。
- 下列正确性已由自动化测试全绿覆盖，本协议**不得**用以反推其结论：
  - Rust Core 单元 **28** · Vertical Slice **9** · Hardening **16**（异常矩阵 8 + `fts_scaling`）· Smoke **10** · Go Server **4** 包
  - Phase 8 FTS 100K Gate（Tree 加载 / save_content / FTS 查询 / 增量同步，1K/10K/100K 三档均达标）
- 凡标注 **「需在真机确认」** 的条目，均为源码中无法从静态阅读确定的 UI/运行时行为（如冲突副本渲染、blob 懒下载回填、release 日志、refresh-token 接线），须在真机执行时补充观察记录，不得臆断为通过或失败。
