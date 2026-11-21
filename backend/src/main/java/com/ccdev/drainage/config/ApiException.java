package com.ccdev.drainage.config;

/**
 * 业务异常：由 GlobalExceptionHandler 统一转成 HTTP 状态码与 JSON 错误体。
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
