package com.lightnote.client.remote;

public record LoginResponse(String token, long expireSeconds) {
}
