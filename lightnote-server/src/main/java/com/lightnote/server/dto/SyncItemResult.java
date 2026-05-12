package com.lightnote.server.dto;

/**
 * 同步成功项模型，表示单条笔记在服务端确认后的版本信息。
 */
public record SyncItemResult(
        String noteUuid,
        long objectVersion,
        long serverVersion
) {
}

