package com.lightnote.server.dto;

public record LoginResponse(
        String token,
        long expireSeconds
) {
}
