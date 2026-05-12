# LightNote 轻量化云同步笔记系统设计文档

版本：V1.1 可实现版  
日期：2026-05-07  
目标平台：Windows 桌面客户端 + 私有 VPS 服务端  
推荐技术栈：Java 17 + JavaFX + SQLite + Spring Boot + MySQL

## 1. 文档目标

本文档用于指导 Codex 或开发者按阶段实现一个轻量化、私有部署、本地优先的云同步笔记系统。

本版本相对原设计书做了以下优化：

- 明确第一版只做个人单用户产品，不做多人协作。
- 明确客户端本地数据库是主要工作副本，服务端是云端同步副本。
- 区分对象版本 `object_version` 和服务端同步版本 `server_version`，避免同步实现混乱。
- 缩小第一版功能范围，优先保证笔记 CRUD、本地检索、登录认证和手动同步可用。
- 将标签、附件、Markdown 预览、自动同步、历史版本等能力放入后续阶段。
- 补齐第一版可执行的目录结构、接口、数据表和验收标准。

## 2. 产品定位

LightNote 是一个面向个人使用的轻量化笔记和备忘录工具，适合记录技术笔记、命令片段、故障排查记录、SQL 片段、运维备忘和日常文本。

核心目标：

- 启动快。
- 本地优先。
- 支持离线编辑。
- 支持卡片式笔记管理。
- 支持标题和正文全文搜索。
- 支持同步到自己的 VPS。
- 数据结构简单，可备份，可迁移。

第一版不追求复杂富文本、多人协作、插件市场和知识库能力。

## 3. 第一版范围

### 3.1 必做功能

客户端：

- 登录。
- 三栏主界面：导航区、笔记卡片列表、编辑区。
- 本地 SQLite 初始化。
- 新建、编辑、删除笔记。
- 自动保存到本地 SQLite。
- 收藏、置顶。
- 按置顶和更新时间排序。
- 标题、正文、摘要本地全文搜索。
- 手动同步按钮。
- 同步状态展示。
- 服务端地址配置。
- 深色主题。

服务端：

- 健康检查接口。
- 用户登录接口。
- JWT 鉴权。
- 笔记 CRUD 接口。
- 同步推送接口。
- 同步拉取接口。
- MySQL 数据表初始化脚本。
- Docker Compose 部署文件。

同步：

- 客户端先上传本地待同步变更。
- 服务端按版本判断冲突。
- 客户端再拉取服务端增量变更。
- 删除使用软删除。
- 冲突时保留服务端版本，并把本地未上传版本保存为冲突副本。

### 3.2 第一版暂不做

- 多用户注册。
- 多人实时协作。
- 标签 UI。
- 附件和图片上传。
- 复杂所见即所得富文本编辑器。
- 图片和附件同步。
- 自动后台同步。
- 端到端加密。
- 历史版本和回收站 UI。
- Web 客户端。
- Android 客户端。

## 4. 总体架构

```text
┌───────────────────────────────────────────────┐
│ Windows 客户端                                 │
│                                               │
│ JavaFX UI                                      │
│ ├─ 导航栏                                      │
│ ├─ 搜索框                                      │
│ ├─ 笔记卡片列表                                │
│ └─ Markdown 文本编辑区                         │
│                                               │
│ 本地能力                                       │
│ ├─ SQLite 本地数据库                           │
│ ├─ SQLite FTS5 全文索引                        │
│ ├─ LocalNoteService                            │
│ ├─ RemoteApiClient                             │
│ └─ SyncService                                 │
└───────────────────────┬───────────────────────┘
                        │ HTTPS REST API
                        ▼
┌───────────────────────────────────────────────┐
│ VPS 服务端                                     │
│                                               │
│ Spring Boot                                    │
│ ├─ AuthController                              │
│ ├─ NoteController                              │
│ ├─ SyncController                              │
│ └─ HealthController                            │
│                                               │
│ MySQL                                          │
│ ├─ tbl_user                                    │
│ ├─ tbl_note                                    │
│ ├─ tbl_sync_log                                │
│ └─ tbl_server_state                            │
└───────────────────────────────────────────────┘
```

## 5. 技术选型

| 层级 | 技术 | 说明 |
| --- | --- | --- |
| 客户端语言 | Java 17 | 客户端和服务端统一技术栈，匹配当前工作机环境 |
| 客户端 UI | JavaFX | 适合 Windows 桌面应用 |
| 本地数据库 | SQLite | 单文件、轻量、易备份 |
| 本地搜索 | SQLite FTS5 | 第一版无需额外搜索服务 |
| HTTP 客户端 | Java HttpClient | 减少依赖 |
| 服务端 | Spring Boot 3.x | 稳定、部署简单 |
| ORM | MyBatis | SQL 可控，便于调试 |
| 服务端数据库 | MySQL 8.0 | VPS 上易部署 |
| 认证 | JWT + BCrypt | 第一版足够 |
| 部署 | Docker Compose + Nginx | 私有 VPS 友好 |

## 6. 核心概念

### 6.1 UUID 与数据库 ID

系统内对象同步统一使用 UUID，不使用数据库自增 ID 作为跨端标识。

- `id`：数据库内部自增主键，只在本库使用。
- `note_uuid`：笔记全局唯一 ID，客户端创建笔记时生成。
- `user_id`：服务端用户 ID，第一版只有一个管理员用户。

### 6.2 对象版本与同步版本

第一版必须区分两个版本字段：

- `object_version`：单条笔记自己的版本。每次该笔记被服务端成功更新后加 1，用于冲突判断。
- `server_version`：服务端全局同步版本。任何同步对象发生变化时，全局加 1，并写入 `tbl_sync_log`，用于客户端增量拉取。

客户端上传笔记时携带 `base_object_version`。如果客户端基于的版本小于服务端当前 `object_version`，说明发生冲突。

## 7. 客户端设计

### 7.1 界面布局

第一版采用三栏布局：

```text
┌─────────────────────────────────────────────────────────┐
│ 搜索框                                      新建 同步 设置 │
├──────────────┬──────────────────────┬───────────────────┤
│ 全部笔记      │ 笔记卡片列表          │ 标题输入框          │
│ 今天          │ ┌──────────────────┐ │ Markdown 编辑区     │
│ 最近 7 天     │ │ 标题              │ │                   │
│ 收藏          │ │ 摘要              │ │                   │
│ 归档          │ │ 分类 更新时间 状态 │ │                   │
│              │ └──────────────────┘ │                   │
└──────────────┴──────────────────────┴───────────────────┘
```

### 7.2 客户端行为

- 启动后立即加载本地 SQLite 笔记列表。
- 新建笔记后立即写入本地，状态为 `DIRTY`。
- 编辑标题或正文时使用防抖保存，推荐 500ms 到 1000ms。
- 删除笔记不物理删除，设置 `is_deleted = 1` 和 `sync_status = DELETE_PENDING`。
- 搜索优先使用 SQLite FTS5。
- 手动点击同步时触发完整同步流程。

### 7.3 同步状态

| 状态 | 含义 |
| --- | --- |
| SYNCED | 本地与服务端一致 |
| DIRTY | 本地有新增或修改，待上传 |
| SYNCING | 正在同步 |
| CONFLICT | 存在冲突，需要用户处理 |
| DELETE_PENDING | 本地删除待上传 |

## 8. 服务端设计

服务端只提供 REST API，不提供后台页面。

### 8.1 响应格式

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

建议约定：

- `code = 0` 表示成功。
- `code != 0` 表示业务错误。
- 未登录或 Token 无效使用 HTTP 401。
- 资源不存在使用 HTTP 404。
- 同步冲突使用 HTTP 409 或在同步响应中返回 `conflictItems`。

### 8.2 接口列表

| 方法 | 路径 | 说明 | 是否需要登录 |
| --- | --- | --- | --- |
| GET | `/api/health` | 健康检查 | 否 |
| POST | `/api/auth/login` | 登录 | 否 |
| GET | `/api/notes` | 查询笔记列表 | 是 |
| POST | `/api/notes` | 新建笔记 | 是 |
| PUT | `/api/notes/{noteUuid}` | 更新笔记 | 是 |
| DELETE | `/api/notes/{noteUuid}` | 软删除笔记 | 是 |
| POST | `/api/sync/push` | 推送客户端本地变更 | 是 |
| GET | `/api/sync/changes` | 拉取服务端增量变更 | 是 |

## 9. 数据库设计

### 9.1 服务端 MySQL

```sql
CREATE TABLE tbl_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(64),
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL
);

CREATE TABLE tbl_note (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    note_uuid VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content MEDIUMTEXT,
    content_format VARCHAR(16) NOT NULL DEFAULT 'HTML',
    summary VARCHAR(512),
    category_name VARCHAR(128),
    is_pinned TINYINT NOT NULL DEFAULT 0,
    is_favorite TINYINT NOT NULL DEFAULT 0,
    is_archived TINYINT NOT NULL DEFAULT 0,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    object_version BIGINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    delete_time DATETIME,
    INDEX idx_user_update (user_id, update_time),
    INDEX idx_user_deleted (user_id, is_deleted),
    INDEX idx_note_uuid (note_uuid)
);

CREATE TABLE tbl_sync_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_uuid VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    server_version BIGINT NOT NULL,
    change_time DATETIME NOT NULL,
    INDEX idx_user_version (user_id, server_version)
);

CREATE TABLE tbl_server_state (
    id BIGINT PRIMARY KEY,
    current_server_version BIGINT NOT NULL
);

INSERT INTO tbl_server_state(id, current_server_version) VALUES (1, 0);
```

说明：

- 第一版暂不单独设计分类表和标签表，避免客户端 UI 和同步范围变大。
- `category_name` 先作为普通文本字段，后续再迁移为分类表。
- 每次笔记新增、更新、删除都必须更新 `tbl_server_state.current_server_version`，并写入 `tbl_sync_log`。

### 9.2 客户端 SQLite

```sql
CREATE TABLE notes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    note_uuid TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    content TEXT,
    content_format TEXT NOT NULL DEFAULT 'HTML',
    summary TEXT,
    category_name TEXT,
    is_pinned INTEGER NOT NULL DEFAULT 0,
    is_favorite INTEGER NOT NULL DEFAULT 0,
    is_archived INTEGER NOT NULL DEFAULT 0,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    object_version INTEGER NOT NULL DEFAULT 0,
    server_version INTEGER NOT NULL DEFAULT 0,
    sync_status TEXT NOT NULL DEFAULT 'DIRTY',
    create_time TEXT NOT NULL,
    update_time TEXT NOT NULL,
    delete_time TEXT,
    last_sync_time TEXT
);

CREATE VIRTUAL TABLE note_fts USING fts5(
    title,
    content,
    summary,
    content='notes',
    content_rowid='id'
);

CREATE TABLE app_config (
    config_key TEXT PRIMARY KEY,
    config_value TEXT
);
```

客户端需要在笔记新增、更新、删除时维护 FTS 索引。可以使用 SQLite trigger，也可以在 `NoteRepository` 中显式更新。第一版推荐显式更新，便于 Codex 生成和调试。

## 10. 同步设计

### 10.1 本地配置

客户端保存以下配置：

| 配置项 | 说明 |
| --- | --- |
| `server_url` | 服务端地址 |
| `jwt_token` | 登录 Token |
| `last_sync_version` | 最近一次成功拉取到的服务端同步版本 |
| `theme` | 主题，第一版默认 dark |

`jwt_token` 第一版可存 SQLite 或配置文件，后续再接 Windows Credential Manager。

### 10.2 同步流程

```text
用户点击同步
  ↓
客户端查询 sync_status in ('DIRTY', 'DELETE_PENDING') 的笔记
  ↓
POST /api/sync/push 上传本地变更
  ↓
服务端逐条判断 base_object_version
  ↓
无冲突：写 tbl_note，增加 object_version，增加 server_version，写 sync_log
有冲突：返回 conflictItems
  ↓
客户端更新成功项的 object_version、server_version、sync_status
  ↓
客户端处理冲突项，生成冲突副本
  ↓
GET /api/sync/changes?sinceVersion={last_sync_version}
  ↓
客户端应用远端变更
  ↓
更新 last_sync_version
```

### 10.3 冲突策略

冲突判断：

```text
如果 client.base_object_version < server.object_version，则冲突。
```

第一版冲突处理：

- 服务端版本保留为原笔记。
- 客户端本地未上传版本另存为新笔记。
- 冲突副本标题格式：`原标题（冲突副本 MM-dd HH:mm）`。
- 冲突副本保留原正文、原 `content_format` 和本地最新编辑内容。
- 冲突副本状态为 `DIRTY`，下次同步作为新笔记上传。

## 11. 同步接口详情

### 11.1 登录

`POST /api/auth/login`

Request:

```json
{
  "username": "admin",
  "password": "123456"
}
```

Response:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "jwt-token",
    "expireSeconds": 7200
  }
}
```

### 11.2 推送本地变更

`POST /api/sync/push`

Request:

```json
{
  "lastSyncVersion": 100,
  "notes": [
    {
      "noteUuid": "uuid-1",
      "operation": "UPDATE",
      "baseObjectVersion": 3,
      "title": "MySQL 慢查询排查",
      "content": "Markdown 内容",
      "contentFormat": "MARKDOWN",
      "summary": "Markdown 内容",
      "categoryName": "数据库",
      "pinned": false,
      "favorite": true,
      "archived": false,
      "deleted": false,
      "clientUpdateTime": "2026-05-07T16:30:00"
    }
  ]
}
```

Response:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "serverVersion": 120,
    "successItems": [
      {
        "noteUuid": "uuid-1",
        "objectVersion": 4,
        "serverVersion": 120
      }
    ],
    "conflictItems": []
  }
}
```

### 11.3 拉取服务端变更

`GET /api/sync/changes?sinceVersion=100&limit=200`

Response:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "serverVersion": 120,
    "hasMore": false,
    "notes": [
      {
        "noteUuid": "uuid-2",
        "operation": "UPDATE",
        "objectVersion": 5,
        "serverVersion": 118,
        "title": "Linux 常用命令",
        "content": "systemctl status nginx",
        "contentFormat": "MARKDOWN",
        "summary": "systemctl status nginx",
        "categoryName": "Linux",
        "pinned": false,
        "favorite": false,
        "archived": false,
        "deleted": false,
        "createTime": "2026-05-07T10:00:00",
        "updateTime": "2026-05-07T16:40:00",
        "deleteTime": null
      }
    ]
  }
}
```

补充说明：

- `contentFormat` 支持 `HTML` 和 `MARKDOWN`。
- 服务端按 `contentFormat` 生成摘要，Markdown 会先去标记再写入 `summary`。
- 客户端拉取到 `HTML` 旧笔记时继续按 HTML 展示或提供单篇转换入口，不会强制自动改写为 Markdown。

## 12. 项目目录结构

```text
lightnote/
├── lightnote-client/
│   ├── pom.xml
│   └── src/main/java/com/lightnote/client/
│       ├── app/
│       ├── config/
│       ├── model/
│       ├── repository/
│       ├── remote/
│       ├── service/
│       ├── sync/
│       └── ui/
│
├── lightnote-server/
│   ├── pom.xml
│   └── src/main/java/com/lightnote/server/
│       ├── config/
│       ├── controller/
│       ├── dto/
│       ├── entity/
│       ├── exception/
│       ├── mapper/
│       ├── security/
│       └── service/
│
├── docs/
│   ├── design.md
│   ├── api.md
│   ├── db.sql
│   └── deploy.md
│
└── docker/
    └── docker-compose.yml
```

## 13. 开发阶段拆解

### 阶段 1：服务端骨架

目标：创建可运行的 Spring Boot 服务端。

任务：

- 创建 `lightnote-server` Maven 项目。
- 引入 Spring Boot Web、MyBatis、MySQL Driver、Spring Security、JWT 依赖。
- 实现 `ApiResponse`。
- 实现全局异常处理。
- 实现 `/api/health`。
- 添加基础配置文件。

验收：

- `mvn package` 通过。
- 启动后访问 `/api/health` 返回成功。

### 阶段 2：服务端认证

目标：完成登录和接口保护。

任务：

- 创建 `db.sql`。
- 初始化管理员用户。
- 实现 BCrypt 密码校验。
- 实现 `POST /api/auth/login`。
- 实现 JWT 生成和校验。
- 保护 `/api/notes/**` 和 `/api/sync/**`。

验收：

- 正确账号密码可登录。
- 错误密码登录失败。
- 未带 Token 访问笔记接口返回 401。

### 阶段 3：服务端笔记与同步

目标：完成服务端笔记存储和同步基础能力。

任务：

- 实现 `NoteEntity`、`NoteMapper`、`NoteService`。
- 实现笔记 CRUD。
- 实现 `POST /api/sync/push`。
- 实现 `GET /api/sync/changes`。
- 每次变更维护 `object_version`、`server_version` 和 `tbl_sync_log`。

验收：

- 可以新增、修改、软删除笔记。
- 推送变更后可从 changes 接口拉取。
- 版本冲突能返回 conflict item。

### 阶段 4：客户端本地版

目标：完成不依赖服务端的本地笔记客户端。

任务：

- 创建 `lightnote-client` JavaFX Maven 项目。
- 实现主页面三栏布局。
- 实现 SQLite 初始化。
- 实现 `NoteRepository`。
- 实现新建、编辑、删除、收藏、置顶。
- 实现卡片列表。
- 实现本地搜索。

验收：

- `mvn javafx:run` 可启动。
- 断网状态下能创建和编辑笔记。
- 重启后笔记不丢失。
- 搜索能命中标题和正文。

### 阶段 5：客户端登录与手动同步

目标：客户端连接服务端并完成同步。

任务：

- 实现登录页。
- 保存 `server_url` 和 `jwt_token`。
- 实现 `RemoteApiClient`。
- 实现 `SyncService`。
- 实现手动同步按钮。
- 实现冲突副本保存。
- 同步完成后刷新卡片列表和状态。

验收：

- 客户端可登录服务端。
- 本地新建笔记后点击同步可上传。
- 服务端变更可拉取到本地。
- 冲突时不会覆盖本地未上传内容。

### 阶段 6：部署与打包

目标：让系统可以在 VPS 和 Windows 上实际使用。

任务：

- 编写 Docker Compose。
- 编写 Nginx 反向代理示例。
- 编写数据库备份脚本。
- 编写 Windows 客户端打包说明。
- 补充 README。

验收：

- VPS 上 `docker compose up -d` 后服务端可用。
- `/api/health` 可访问。
- 备份脚本能生成 MySQL 压缩备份。
- Windows 客户端可启动连接 VPS。

## 14. 部署设计

VPS 推荐目录：

```text
/opt/lightnote/
├── docker-compose.yml
├── server/
│   └── lightnote-server.jar
├── data/
│   ├── mysql/
│   └── backup/
└── logs/
```

`docker-compose.yml` 示例：

```yaml
version: "3.8"

services:
  mysql:
    image: mysql:8.0
    container_name: lightnote-mysql
    restart: always
    environment:
      MYSQL_ROOT_PASSWORD: change_this_root_password
      MYSQL_DATABASE: lightnote
      MYSQL_USER: lightnote
      MYSQL_PASSWORD: change_this_password
    volumes:
      - ./data/mysql:/var/lib/mysql
    networks:
      - lightnote-net

  server:
    image: eclipse-temurin:17-jre
    container_name: lightnote-server
    restart: always
    depends_on:
      - mysql
    volumes:
      - ./server:/app
      - ./logs:/logs
    working_dir: /app
    command: java -jar lightnote-server.jar
    ports:
      - "8080:8080"
    networks:
      - lightnote-net

networks:
  lightnote-net:
    driver: bridge
```

生产环境建议：

- MySQL 不直接暴露公网端口。
- 服务端前面加 Nginx。
- Nginx 配置 HTTPS。
- 数据库密码、JWT 密钥使用环境变量注入。

## 15. 安全设计

第一版安全要求：

- 密码使用 BCrypt，不保存明文。
- 所有业务接口必须校验 JWT。
- 服务端部署必须启用 HTTPS。
- MySQL 端口不暴露公网。
- 备份文件放在 VPS 私有目录中。

后续增强：

- Windows Credential Manager 保存 Token。
- 客户端本地数据库加密。
- 端到端加密。
- 登录失败限流。
- 操作审计日志。

## 16. 验收标准

| 验收项 | 通过标准 |
| --- | --- |
| 启动速度 | 客户端 2 秒内展示本地笔记列表 |
| 本地保存 | 断网编辑后重启，内容不丢失 |
| 新建笔记 | 新建后立即出现在卡片列表 |
| 编辑笔记 | 修改标题或正文后自动保存 |
| 删除笔记 | 本地和服务端均软删除 |
| 搜索 | 标题和正文关键词能搜索到 |
| 排序 | 置顶优先，其次更新时间倒序 |
| 登录 | 正确账号密码返回 JWT |
| 鉴权 | 未带 Token 访问业务接口返回 401 |
| 上传同步 | 本地新增或修改可上传服务端 |
| 拉取同步 | 服务端变更可拉取到客户端 |
| 冲突处理 | 多端修改不覆盖本地未上传内容 |
| 部署 | VPS 上健康检查接口可访问 |
| 备份 | 执行脚本后生成数据库压缩备份 |

## 17. 后续规划

第二阶段：

- 分类表和分类管理。
- 标签表和标签筛选。
- Markdown 预览。
- 代码高亮。
- 自动同步。
- 回收站 UI。

第三阶段：

- 附件和图片上传。
- 历史版本。
- Web 只读端。
- Android 客户端。
- 端到端加密。
- 导出 Markdown、HTML、PDF。
- 与 NAS 或对象存储备份集成。

## 18. 推荐 Codex 执行提示词

### 18.1 创建服务端骨架

```text
请基于 docs/design.md 实现 lightnote-server 第一阶段。
要求：
1. 使用 Java 17、Spring Boot、MyBatis、MySQL。
2. 创建 Maven 项目结构。
3. 实现 ApiResponse、全局异常处理和 GET /api/health。
4. 暂不实现认证和笔记业务。
5. 代码必须能 mvn package 通过。
```

### 18.2 实现认证模块

```text
请在 lightnote-server 中实现认证模块。
要求：
1. 根据 docs/db.sql 创建 tbl_user 表。
2. 使用 BCrypt 校验密码。
3. 实现 POST /api/auth/login。
4. 登录成功返回 JWT。
5. 实现 JWT Filter，保护 /api/notes 和 /api/sync。
6. 补充 README 中的启动和测试说明。
```

### 18.3 实现客户端本地版

```text
请基于 docs/design.md 实现 lightnote-client 本地版。
要求：
1. 使用 Java 17 + JavaFX。
2. 实现三栏主界面。
3. 本地使用 SQLite，启动时自动建表。
4. 实现新建、编辑、删除、收藏、置顶。
5. 中间区域使用卡片式列表展示笔记。
6. 实现标题和正文搜索。
7. 暂不连接服务端。
8. 代码必须能 mvn javafx:run 运行。
```

### 18.4 实现手动同步

```text
请为 lightnote-client 和 lightnote-server 实现手动同步。
要求：
1. 客户端扫描 sync_status = DIRTY 或 DELETE_PENDING 的笔记。
2. 调用 POST /api/sync/push 上传变更。
3. 服务端使用 baseObjectVersion 判断冲突。
4. 服务端维护 object_version、server_version 和 tbl_sync_log。
5. 客户端调用 GET /api/sync/changes 拉取远端变更。
6. 冲突时客户端生成冲突副本，不覆盖本地未上传内容。
7. 补充最小可验证测试步骤。
```
