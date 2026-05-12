package com.lightnote.server.dto;

import java.time.LocalDateTime;

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
