package com.lightnote.client.model;

/**
 * 本地同步状态枚举，用于描述笔记是否已同步、待同步或存在冲突。
 */
public enum SyncStatus {
    SYNCED,
    DIRTY,
    SYNCING,
    CONFLICT,
    DELETE_PENDING
}

