package com.lightnote.client.remote;

import com.lightnote.client.model.ContentFormat;

public record RemoteNote(
        String noteUuid,
        String operation,
        long objectVersion,
        long serverVersion,
        String contentFormat,
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
    public RemoteNote(
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
        this(
                noteUuid,
                operation,
                objectVersion,
                serverVersion,
                ContentFormat.HTML.name(),
                title,
                content,
                summary,
                categoryName,
                pinned,
                favorite,
                archived,
                deleted,
                createTime,
                updateTime,
                deleteTime
        );
    }
}
