package com.lightnote.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SyncPushRequest(
        @NotNull Long lastSyncVersion,
        @Valid List<SyncNoteRequest> notes
) {
}
