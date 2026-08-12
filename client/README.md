# LightNote Client

Tauri 2 桌面客户端，**Agent 1 (Rust Core)** 与 **Agent 3 (Vue UI)** 负责此目录。

## 目录所有权

```text
client/
├── src/          # Vue 3 + TypeScript UI     → Agent 3
└── src-tauri/    # Rust Core                  → Agent 1
    ├── db/       # SQLite / Migration / Repository
    ├── sync/     # Change Log / Outbox / Cursor / Sync Engine
    ├── blob/     # Blob Manager / Download Queue（Agent 4 协作）
    ├── search/   # FTS5
    └── api/      # HTTP Client
```

## 契约文件

- 数据库结构：`docs/schema/common.sql` + `docs/schema/client.sql`
- Tauri IPC：`docs/ipc.md`
- 同步协议：`docs/change-protocol.md`
