package com.lightnote.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SyncNoteRequest(
        @NotBlank String noteUuid,
        @NotBlank String operation,
        @NotNull Long baseObjectVersion,
        @Size(max = 255) String title,
        String content,
        @Size(max = 16) String contentFormat,
        @Size(max = 512) String summary,
        @Size(max = 128) String categoryName,
        Boolean pinned,
        Boolean favorite,
        Boolean archived,
        Boolean deleted,
        String clientUpdateTime
) {
    public SyncNoteRequest(
            String noteUuid,
            String operation,
            Long baseObjectVersion,
            String title,
            String content,
            String summary,
            String categoryName,
            Boolean pinned,
            Boolean favorite,
            Boolean archived,
            Boolean deleted,
            String clientUpdateTime
    ) {
        this(
                noteUuid,
                operation,
                baseObjectVersion,
                title,
                content,
                null,
                summary,
                categoryName,
                pinned,
                favorite,
                archived,
                deleted,
                clientUpdateTime
        );
    }
}
