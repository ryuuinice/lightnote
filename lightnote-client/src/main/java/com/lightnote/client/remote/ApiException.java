package com.lightnote.client.remote;

/**
 * 表示远端接口访问失败或服务端返回异常时抛出的客户端异常。
 */
public class ApiException extends RuntimeException {

    public ApiException(String message) {
        super(message);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}

