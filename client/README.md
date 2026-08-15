# LightNote Client

Tauri 2 桌面客户端，**Agent 1 (Rust Core)** 与 **Agent 3 (Vue UI)** 负责此目录。

## 目录所有权

```text
client/
├── src/          # Vue 3 + TypeScript UI     → Agent 3
├── src-tauri/    # Rust Core (lightnote_core) → Agent 1
│   ├── db/       # SQLite / Migration / Repository
│   ├── sync/     # Change Log / Outbox / Cursor / Sync Engine
│   ├── blob/     # Blob Manager / Download Queue（Agent 4 协作）
│   ├── search/   # FTS5
│   └── api/      # HTTP Client
└── app/          # Tauri 2 桌面壳 (lightnote_app) → Agent 1
```

## 开发

```bash
npm install
npm run dev       # Vite 开发服务器（:5173）

# 另开终端
cd app && cargo run   # 启动 Tauri 壳
```

`app/tauri.conf.json` 中 `devUrl` 指向 Vite 开发服务器，`frontendDist` 指向 `../dist`（`npm run build` 产物）。

## 契约文件

- 数据库结构：`docs/schema/common.sql` + `docs/schema/client.sql`
- Tauri IPC：`docs/ipc.md`
- 同步协议：`docs/change-protocol.md`
