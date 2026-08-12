# LightNote Server

Go 单二进制服务端，**Agent 2 (Go Server)** 负责此目录。

## 目录所有权

```text
server/
├── cmd/lightnote-server/   # 入口
└── internal/
    ├── config/             # env + config.yaml
    ├── db/                 # SQLite / Migration / Repository
    ├── auth/               # JWT / Device
    ├── api/                # HTTP Handler
    └── sync/               # Push / Pull / Sequence / Conflict
```

## 契约文件

- 数据库结构：`docs/schema/common.sql` + `docs/schema/server.sql`
- REST API：`docs/openapi.yaml`
- 同步协议：`docs/change-protocol.md`
