# LightNote API v1.1

> **状态：** Contract Freeze  
> **所有者：** Agent 0  
> **机器可读版本：** `docs/openapi.yaml`（本文件为人工阅读摘要，以 openapi.yaml 为准）  
> **基础路径：** `/api/v1`  
> **认证：** `Authorization: Bearer <JWT>`

---

# 1. 认证（TASK-007 最小 JWT Contract）

## 1.1 模型

```text
单用户 + 多设备
users → devices → sync_state
```

## 1.2 JWT Claims

```json
{
  "sub": "user-001",
  "device_id": "device-a"
}
```

| Claim | 说明 |
|---|---|
| sub | user_id |
| device_id | 设备身份，**服务端签发**，登录时确认/注册到 devices 表 |

## 1.3 硬性规则（Device Identity Binding）

```text
1. 服务端所有接口从 JWT 解析 device_id
2. 不信任请求体 / Header 中自报的 device_id
3. entity_changes.origin_device_id 以 JWT 的 device_id 为准
4. 设备已吊销（devices.revoked_at 非 NULL）→ 全部 Token 拒绝
```

## 1.4 最小 JWT 阶段范围（v1.1）

| 功能 | 阶段 |
|---|---|
| Access Token（HS256，2h）+ claims | ✅ TASK-007（Vertical Slice 前必须完成） |
| Refresh Token（30 天，轮换制，设备吊销传播） | ✅ Phase 6 |
| 设备列表 / 吊销 | ✅ Phase 6（接口已定义） |
| Token 过期刷新 | ✅ Phase 6 |

## 1.5 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/login` | username + password + device_name → access_token + refresh_token + device_id |
| POST | `/auth/refresh` | refresh_token → 新 access_token + 轮换的新 refresh_token（旧 token 立即失效） |

Refresh 语义（Phase 6 冻结）：

```text
1. refresh_token 以 SHA-256 哈希存储（服务端不存明文）
2. 每次刷新轮换：旧 token 标记 revoked，签发新 token
3. 已轮换 token 复用 → 401 INVALID_REFRESH_TOKEN
4. 设备已吊销 → 403 DEVICE_REVOKED，且该设备全部 refresh_token 一并吊销
5. 有效期 30 天
```

登录示例：

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123","device_name":"PC-Windows","device_type":"desktop"}'
```

响应：

```json
{
  "access_token": "eyJ...",
  "refresh_token": "",
  "expires_in": 7200,
  "device_id": "01J...device"
}
```

---

# 2. Sync

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/sync/push` | 批量 Push（≤1000 条，≤8MB） |
| GET | `/sync/changes?after=&limit=` | 增量 Pull（升序，容忍 gap，has_more 翻页） |

协议细节见 `docs/change-protocol.md`。

---

# 3. Notes / Branches / Attributes

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/notes?parent_note_id=&include_deleted=` | 树形子节点列表（元数据，懒加载） |
| GET | `/notes/{note_id}` | 笔记详情（正文经 blob_id 获取） |
| DELETE | `/notes/{note_id}` | Tombstone 删除（产生 DELETE Change） |
| GET | `/branches?parent_note_id=` | 分支列表（sort_order 升序） |
| GET | `/notes/{note_id}/attributes` | 标签/关系/属性列表 |

> 客户端一切写操作走 Sync 协议（本地 SQLite 事务 → Change → Push）。
> 服务端 Notes/Branches/Attributes 端点以只读为主（后续按需扩展写端点，须经 Agent 0）。

---

# 4. Blob

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/blobs/init` | 初始化上传：{blob_id, size, mime_type} → EXISTS / CREATED |
| PUT | `/blobs/{blob_id}/chunks/{index}` | 分片上传（4~16MB），重复分片安全忽略 |
| POST | `/blobs/{blob_id}/complete` | 完成：服务端重算 SHA-256 与 blob_id 校验 |
| GET | `/blobs/{blob_id}` | 下载（懒下载队列使用） |

---

# 5. Devices（Phase 6 启用）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/devices` | 设备列表（含 last_seen / revoked_at） |
| DELETE | `/devices/{device_id}` | 吊销设备 |

---

# 6. 健康检查

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/healthz` | 无认证；返回 `{"status":"ok"}`（部署探活用） |

---

# 7. 通用错误格式

```json
{
  "code": "INVALID_CREDENTIALS",
  "message": "用户名或密码错误"
}
```

| HTTP | code | 说明 |
|---|---|---|
| 400 | INVALID_DATA | 请求体/参数非法 |
| 401 | UNAUTHORIZED | 未认证 / Token 失效 / 设备吊销 |
| 403 | DEVICE_REVOKED | 设备已吊销 |
| 404 | NOT_FOUND | 资源不存在 |
| 409 | VERSION_CONFLICT | 版本冲突（Push 响应逐条返回 CONFLICT） |
| 413 | PAYLOAD_TOO_LARGE | Push 超过 8MB |
| 500 | INTERNAL | 服务端内部错误 |
