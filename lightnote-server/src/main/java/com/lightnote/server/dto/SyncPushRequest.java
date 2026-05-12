package com.lightnote.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 请求模型，用于接收客户端提交的接口参数。
 */
public record SyncPushRequest(
        @NotNull Long lastSyncVersion,
        @Valid List<SyncNoteRequest> notes
) {
}

