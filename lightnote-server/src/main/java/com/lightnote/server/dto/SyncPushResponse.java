package com.lightnote.server.dto;

import java.util.List;

public record SyncPushResponse(
        long serverVersion,
        List<SyncItemResult> successItems,
        List<SyncConflictItem> conflictItems
) {
}
