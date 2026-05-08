package com.lightnote.server.dto;

public record SyncItemResult(
        String noteUuid,
        long objectVersion,
        long serverVersion
) {
}
