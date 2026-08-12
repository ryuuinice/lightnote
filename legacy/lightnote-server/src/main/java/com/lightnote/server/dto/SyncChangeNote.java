package com.lightnote.server.dto;

import java.time.LocalDateTime;

/**
 * 同步或接口层使用的笔记数据模型。
 */
public record SyncChangeNote(
        String noteUuid,
        String operation,
        long objectVersion,
        long serverVersion,
        String title,
        String content,
        String contentFormat,
        String summary,
        String categoryName,
        boolean pinned,
        boolean favorite,
        boolean archived,
        boolean deleted,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        LocalDateTime deleteTime
) {
    public SyncChangeNote(
            String noteUuid,
            String operation,
            long objectVersion,
            long serverVersion,
            String title,
            String content,
            String summary,
            String categoryName,
            boolean pinned,
            boolean favorite,
            boolean archived,
            boolean deleted,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            LocalDateTime deleteTime
    ) {
        this(
                noteUuid,
                operation,
                objectVersion,
                serverVersion,
                title,
                content,
                null,
                summary,
                categoryName,
                pinned,
                favorite,
                archived,
                deleted,
                createTime,
                updateTime,
                deleteTime
        );
    }
}

