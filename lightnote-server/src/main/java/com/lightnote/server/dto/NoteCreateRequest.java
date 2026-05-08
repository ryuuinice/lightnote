package com.lightnote.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NoteCreateRequest(
        @NotBlank @Size(max = 255) String title,
        String content,
        @Size(max = 512) String summary,
        @Size(max = 128) String categoryName,
        Boolean pinned,
        Boolean favorite,
        Boolean archived
) {
}
