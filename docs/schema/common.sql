-- LightNote Schema - common.sql
-- 唯一数据库结构真相源（客户端与服务端共享部分）
-- 契约文档，修改必须经过 Agent 0（见开发计划 §52）
--
-- 约定：
--   * 时间戳一律为 INTEGER Unix 毫秒
--   * ID 一律为 TEXT（UUIDv7 / ULID）
--   * 删除一律为 Tombstone（is_deleted = 1），禁止物理 DELETE 业务实体
--   * blob_id 即内容寻址：blob_id = 'sha256:' || hex(SHA-256(content))
--   * entity_changes 行永不物理删除（见架构 v1.1 §22.1 规则 3）

-- ---------------------------------------------------------------------------
-- 迁移版本追踪（客户端与服务端共用）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schema_migrations (
    version    INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- users（v1.1 单用户 + 多设备，保留表结构为多用户预留）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    user_id       TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at    INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- notes
-- 内容与元数据分离：正文/附件经 blob_id 指向 blobs 表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notes (
    note_id            TEXT PRIMARY KEY,
    title              TEXT NOT NULL DEFAULT '',
    note_type          TEXT NOT NULL DEFAULT 'text',
    blob_id            TEXT,
    is_deleted         INTEGER NOT NULL DEFAULT 0,
    version            INTEGER NOT NULL DEFAULT 1,
    updated_at         INTEGER NOT NULL,
    updated_by         TEXT,
    created_at         INTEGER NOT NULL,
    conflict_of_note_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_notes_is_deleted ON notes(is_deleted);
CREATE INDEX IF NOT EXISTS idx_notes_updated_at  ON notes(updated_at);
CREATE INDEX IF NOT EXISTS idx_notes_blob_id     ON notes(blob_id);

-- ---------------------------------------------------------------------------
-- branches
-- 树形组织的边：一个 note 可挂多个父节点（多父节点 / 克隆）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS branches (
    branch_id      TEXT PRIMARY KEY,
    parent_note_id TEXT NOT NULL,
    child_note_id  TEXT NOT NULL,
    sort_order     INTEGER NOT NULL DEFAULT 0,
    is_deleted     INTEGER NOT NULL DEFAULT 0,
    version        INTEGER NOT NULL DEFAULT 1,
    updated_at     INTEGER NOT NULL,
    updated_by     TEXT,
    created_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_branches_parent
    ON branches(parent_note_id, is_deleted, sort_order);
CREATE INDEX IF NOT EXISTS idx_branches_child
    ON branches(child_note_id, is_deleted);

-- ---------------------------------------------------------------------------
-- attributes
-- 标签 / 关系 / 扩展属性：attr_type = 'label' | 'relation' | 'meta'
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS attributes (
    attribute_id TEXT PRIMARY KEY,
    note_id      TEXT NOT NULL,
    attr_type    TEXT NOT NULL,
    name         TEXT NOT NULL,
    value        TEXT,
    is_inherited INTEGER NOT NULL DEFAULT 0,
    is_deleted   INTEGER NOT NULL DEFAULT 0,
    version      INTEGER NOT NULL DEFAULT 1,
    updated_at   INTEGER NOT NULL,
    updated_by   TEXT,
    created_at   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_attributes_note
    ON attributes(note_id, is_deleted);

-- ---------------------------------------------------------------------------
-- blobs
-- 内容实体（Markdown 正文 / 图片 / PDF / Office / 附件）
-- blob_id = 'sha256:' || hex(SHA-256(content))，内容寻址天然去重
-- storage_type = 'file'（默认，文件系统存储）| 'inline'
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS blobs (
    blob_id      TEXT PRIMARY KEY,
    size         INTEGER NOT NULL,
    mime_type    TEXT,
    storage_type TEXT NOT NULL DEFAULT 'file',
    storage_path TEXT NOT NULL,
    created_at   INTEGER NOT NULL
);

-- ---------------------------------------------------------------------------
-- entity_changes
-- 同步核心：每个实体修改先记 Change Log，与实体写入同一事务（架构 v1.1 §22）
-- server_sequence 仅服务端分配；客户端本地修改时为 NULL，Pull 应用后回填
--
-- 约束（架构 v1.1 §22.1）：
--   * 严格单调、永不重复 → 服务端经 sync_sequence 表 AUTOINCREMENT 分配
--   * 本表行永不物理删除（Change Log GC 前必须先解决水位约束）
--   * 不采用 is_synced 字段；待发送状态由 sync_outbox 表达
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS entity_changes (
    change_id        TEXT PRIMARY KEY,
    origin_device_id TEXT NOT NULL,
    entity_type      TEXT NOT NULL,
    entity_id        TEXT NOT NULL,
    operation        TEXT NOT NULL,
    base_version     INTEGER NOT NULL,
    version          INTEGER NOT NULL,
    server_sequence  INTEGER,
    content_hash     TEXT,
    payload          TEXT NOT NULL,
    created_at       INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_entity_changes_sequence
    ON entity_changes(server_sequence);
CREATE INDEX IF NOT EXISTS idx_entity_changes_entity
    ON entity_changes(entity_type, entity_id);
