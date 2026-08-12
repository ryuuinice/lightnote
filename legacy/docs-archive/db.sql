CREATE DATABASE IF NOT EXISTS lightnote
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE lightnote;

CREATE TABLE IF NOT EXISTS tbl_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(64),
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL
);

-- Default development account: admin / admin123.
-- Replace this password before production use.
INSERT INTO tbl_user(username, password_hash, nickname, status, create_time, update_time)
SELECT 'admin', '$2a$10$lmGhjJ.Azab.NpW60YtL5u5d/QFvKaK7zy6jqHDAEIYi8jtn/lKVu', 'Administrator', 1, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM tbl_user WHERE username = 'admin'
);

CREATE TABLE IF NOT EXISTS tbl_note (
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
    server_version BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    delete_time DATETIME,
    INDEX idx_user_update (user_id, update_time),
    INDEX idx_user_deleted (user_id, is_deleted),
    INDEX idx_note_uuid (note_uuid)
);

CREATE TABLE IF NOT EXISTS tbl_sync_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_uuid VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    server_version BIGINT NOT NULL,
    change_time DATETIME NOT NULL,
    INDEX idx_user_version (user_id, server_version)
);

CREATE TABLE IF NOT EXISTS tbl_server_state (
    id BIGINT PRIMARY KEY,
    current_server_version BIGINT NOT NULL
);

INSERT INTO tbl_server_state(id, current_server_version)
SELECT 1, 0
WHERE NOT EXISTS (
    SELECT 1 FROM tbl_server_state WHERE id = 1
);
