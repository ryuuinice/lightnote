package com.lightnote.server.security;

/**
 * 登录用户主体，承载当前认证用户的核心身份信息。
 */
public record UserPrincipal(Long userId, String username) {
}

