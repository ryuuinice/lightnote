package com.lightnote.server.dto;

import java.util.List;

/**
 * 同步推送响应模型，承载成功项、冲突项和最新服务端版本。
 */
public record SyncPushResponse(
        long serverVersion,
        List<SyncItemResult> successItems,
        List<SyncConflictItem> conflictItems
) {
}

