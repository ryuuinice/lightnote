package com.lightnote.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NoteCreateRequest(
        @NotBlank @Size(max = 255) String title,
        String content,
        @Size(max = 16) String contentFormat,
        @Size(max = 512) String summary,
        @Size(max = 128) String categoryName,
        Boolean pinned,
        Boolean favorite,
        Boolean archived
) {
    public NoteCreateRequest(
            String title,
            String content,
            String summary,
            String categoryName,
            Boolean pinned,
            Boolean favorite,
            Boolean archived
    ) {
        this(title, content, null, summary, categoryName, pinned, favorite, archived);
    }
}
