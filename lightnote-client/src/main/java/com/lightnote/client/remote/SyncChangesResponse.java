package com.lightnote.client.remote;

import java.util.List;

public record SyncChangesResponse(
        long serverVersion,
        boolean hasMore,
        List<RemoteNote> notes
) {
}
