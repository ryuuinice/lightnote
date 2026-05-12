package com.lightnote.server.dto;

import java.time.LocalDateTime;

/**
 * 响应模型，用于向调用方返回结构化结果。
 */
public record NoteResponse(
        String noteUuid,
        String title,
        String content,
        String contentFormat,
        String summary,
        String categoryName,
        boolean pinned,
        boolean favorite,
        boolean archived,
        boolean deleted,
        long objectVersion,
        long serverVersion,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        LocalDateTime deleteTime
) {
    public NoteResponse(
            String noteUuid,
            String title,
            String content,
            String summary,
            String categoryName,
            boolean pinned,
            boolean favorite,
            boolean archived,
            boolean deleted,
            long objectVersion,
            long serverVersion,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            LocalDateTime deleteTime
    ) {
        this(
                noteUuid,
                title,
                content,
                null,
                summary,
                categoryName,
                pinned,
                favorite,
                archived,
                deleted,
                objectVersion,
                serverVersion,
                createTime,
                updateTime,
                deleteTime
        );
    }
}

