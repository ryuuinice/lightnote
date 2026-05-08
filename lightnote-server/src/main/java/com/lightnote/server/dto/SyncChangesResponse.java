package com.lightnote.server.dto;

import java.util.List;

public record SyncChangesResponse(
        long serverVersion,
        boolean hasMore,
        List<SyncChangeNote> notes
) {
}
