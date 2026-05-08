package com.lightnote.server.dto;

import java.time.LocalDateTime;

public record SyncChangeNote(
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
}
