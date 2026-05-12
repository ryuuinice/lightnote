package com.lightnote.server.dto;

import java.util.List;

/**
 * 同步拉取响应模型，承载分页增量变化及服务端版本游标。
 */
public record SyncChangesResponse(
        long serverVersion,
        boolean hasMore,
        List<SyncChangeNote> notes
) {
}

