# LightNote

轻量化、本地优先、支持私有云同步的桌面笔记系统。

## 架构

当前技术栈为 **Go + Rust + Tauri 2 + Vue 3**（v1.1 重构后的实现，旧 Java 实现已归档至 `legacy/`）。

```text
.
├── client/
│   ├── src/          # Vue 3 + TypeScript UI
│   ├── src-tauri/    # Rust 核心 lightnote_core（SQLite / 同步引擎 / FTS5 / Blob）
│   └── app/          # Tauri 2 桌面壳 lightnote_app
├── server/           # Go 单二进制服务端（HTTP / JWT / Push-Pull 同步 / Blob）
├── tests/            # QA 测试 crate（smoke / vertical-slice / perf / hardening）
├── docs/             # 冻结契约与架构文档（openapi.yaml / ipc.md / change-protocol.md / schema）
└── legacy/           # 旧 Java 实现归档（冻结，不再维护）
```

## 环境要求

- Go 1.23+
- Rust（stable）+ Cargo
- Node.js 18+ / npm

## 构建与运行

### 服务端

```bash
cd server
cp config.example.yaml config.yaml   # 按需修改监听地址、数据库路径、JWT 密钥等
go run ./cmd/lightnote-server
```

服务端默认地址：`http://localhost:8080`

> 注意：首次启动会按配置创建初始用户，请在生产环境中修改 `config.yaml` 中的默认值并设置强密码与 JWT 密钥。

### 客户端

```bash
cd client
npm install
npm run dev          # 启动 Vue 前端开发服务器（Vite, :5173）

# 另开终端，启动 Tauri 桌面壳
cd client/app
cargo run
```

### 运行测试

```bash
# 服务端（Go）
cd server && go test ./...

# 客户端核心（Rust）
cd client/src-tauri && cargo test

# QA 集成测试（smoke / vertical-slice / perf / hardening）
cd tests/smoke && cargo test
```

## 契约与文档

- REST API：[docs/openapi.yaml](docs/openapi.yaml)、[docs/api.md](docs/api.md)
- 同步协议：[docs/change-protocol.md](docs/change-protocol.md)
- Tauri IPC：[docs/ipc.md](docs/ipc.md)
- 数据库结构：`docs/schema/`（common / client / server）
- 架构设计：[docs/architecture/](docs/architecture/)
- 目录所有权（多代理分工）：[docs/architecture/OWNERSHIP.md](docs/architecture/OWNERSHIP.md)
- 变更日志：[docs/CHANGELOG.md](docs/CHANGELOG.md)

## 目录详细说明

- [server/README.md](server/README.md) — 服务端
- [client/README.md](client/README.md) — 客户端
- [tests/README.md](tests/README.md) — 测试
