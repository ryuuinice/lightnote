package com.lightnote.client.remote;

public record RemoteNote(
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
        String createTime,
        String updateTime,
        String deleteTime
) {
}
