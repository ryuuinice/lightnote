package com.lightnote.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 请求模型，用于接收客户端提交的接口参数。
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {
}

