package com.lightnote.server.exception;

/**
 * 服务端业务异常，表示可预期的业务失败场景。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}

