-- LightNote Schema - server.sql
-- 服务端专有结构（Go 单二进制 + SQLite）
-- 契约文档，修改必须经过 Agent 0（见开发计划 §52）
--
-- 约定：同 common.sql
--
-- 注意：服务端必须开启 WAL + busy_timeout（见架构 v1.1 §42/§66），
-- 写连接保持单写者，保证 server_sequence 严格单调。

-- ---------------------------------------------------------------------------
-- sync_sequence
-- server_sequence 分配器（架构 v1.1 §22.1 规则 1）
--
-- 为什么需要：server_sequence 必须是 SQLite AUTOINCREMENT 分配的严格单调、
-- 永不重复（回滚不复用）的整数。entity_changes 的 PK 是 change_id (TEXT)，
-- 因此用本表承担 AUTOINCREMENT 分配：
--
--   BEGIN
--     INSERT INTO entity_changes (...) VALUES (...);          -- 或先插序列
--     INSERT INTO sync_sequence DEFAULT VALUES RETURNING seq; -- 同事务分配
--     ... 用返回的 seq 更新 entity_changes.server_sequence ...
--   COMMIT
--
-- 禁止使用 SELECT MAX(server_sequence)+1 手动分配（并发/回滚会产生重复）。
-- 本表行只增不删；回滚产生的 gap 是正常现象，Pull 按 `>` 过滤容忍。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sync_sequence (
    seq INTEGER PRIMARY KEY AUTOINCREMENT
);

-- ---------------------------------------------------------------------------
-- devices
-- 已注册设备。device_id 与 JWT claims 绑定（架构 v1.1 §9.2），
-- 服务端不信任客户端自报 device_id。
-- revoked_at 非 NULL 即设备已吊销，其 Access/Refresh Token 拒绝。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS devices (
    device_id    TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL,
    device_name  TEXT NOT NULL,
    device_type  TEXT,
    last_seen    INTEGER,
    revoked_at   INTEGER,
    created_at   INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

-- ---------------------------------------------------------------------------
-- device_sync_state
-- 每设备同步水位（架构 v1.1 §10）
-- 用途：Tombstone GC / Change Log GC 水位、长期离线设备检测、设备状态展示
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_sync_state (
    device_id            TEXT PRIMARY KEY,
    last_server_sequence INTEGER NOT NULL DEFAULT 0,
    last_seen            INTEGER,
    updated_at           INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- 服务端刷新令牌（v1.1 最小 JWT 阶段可暂缓，Phase 6 启用）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS refresh_tokens (
    token_hash  TEXT PRIMARY KEY,
    device_id   TEXT NOT NULL,
    expires_at  INTEGER NOT NULL,
    revoked_at  INTEGER,
    created_at  INTEGER NOT NULL,
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_device
    ON refresh_tokens(device_id);
