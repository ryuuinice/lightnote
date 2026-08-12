-- LightNote Schema - client.sql
-- 客户端专有结构（Tauri + Rust 本地 SQLite）
-- 契约文档，修改必须经过 Agent 0（见开发计划 §52）
--
-- 约定：同 common.sql

-- ---------------------------------------------------------------------------
-- sync_outbox
-- 本地待发送队列。只存 change_id，Change 内容从 entity_changes 读取。
-- Pull 应用远端 Change 时禁止写入本表（防同步环路，架构 v1.1 §19/§30）。
-- state: 'PENDING' | 'SENDING'
--   SENDING 超时（进程崩溃）后自动恢复为 PENDING（架构 v1.1 §27）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sync_outbox (
    change_id     TEXT PRIMARY KEY,
    state         TEXT NOT NULL DEFAULT 'PENDING',
    retry_count   INTEGER NOT NULL DEFAULT 0,
    next_retry_at INTEGER NOT NULL DEFAULT 0,
    last_error    TEXT,
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL,
    FOREIGN KEY (change_id) REFERENCES entity_changes(change_id)
);

CREATE INDEX IF NOT EXISTS idx_sync_outbox_pending
    ON sync_outbox(state, next_retry_at);

-- ---------------------------------------------------------------------------
-- sync_state
-- 同步游标：已成功应用的最大 server_sequence
-- cursor 与批量应用的 Change 在同一事务提交（架构 v1.1 §65）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sync_state (
    client_id            TEXT PRIMARY KEY,
    last_server_sequence INTEGER NOT NULL DEFAULT 0,
    updated_at           INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- blob_download_queue
-- Blob 懒下载队列：实体 Change 先应用，缺失 Blob 后台下载
-- state: 'PENDING' | 'DOWNLOADING' | 'FAILED'
-- 下载完成 → SHA-256 校验 → 原子 rename 落盘 → 删除队列记录
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS blob_download_queue (
    blob_id      TEXT PRIMARY KEY,
    priority     INTEGER NOT NULL DEFAULT 0,
    state        TEXT NOT NULL DEFAULT 'PENDING',
    retry_count  INTEGER NOT NULL DEFAULT 0,
    next_retry_at INTEGER NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_blob_queue_pickup
    ON blob_download_queue(state, priority DESC, next_retry_at);

-- ---------------------------------------------------------------------------
-- note_fts
-- 全文搜索（FTS5）：本地派生数据，不参与同步（架构 v1.1 §45-47）
-- 内容 = notes.title + blob 正文（仅 text/markdown）+ 标签名
-- 由应用代码在实体变更事务内同步维护；损坏时可 DROP 重建
-- 分词器：unicode61（迁移 v3，2026-08-11）
--   CJK 策略：索引时对正文中的 CJK 字符逐字加空格（如 "所有权是" → "所 有 权 是"），
--   查询时 CJK 逐字前缀 AND，实现中文子串命中；英文按词前缀匹配
-- ---------------------------------------------------------------------------
CREATE VIRTUAL TABLE IF NOT EXISTS note_fts USING fts5(
    note_id UNINDEXED,
    title,
    content,
    tags,
    tokenize = 'unicode61'
);
