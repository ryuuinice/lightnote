package com.lightnote.client.remote;

import java.util.List;

public record SyncPushResponse(
        long serverVersion,
        List<SyncItemResult> successItems,
        List<SyncConflictItem> conflictItems
) {
}
