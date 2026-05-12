package com.lightnote.client.remote;

/**
 * 登录响应模型，承载 JWT 令牌和有效期信息。
 */
public record LoginResponse(String token, long expireSeconds) {
}

