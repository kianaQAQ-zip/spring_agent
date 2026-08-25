package com.ecomagent.common;

import java.time.Instant;

/**
 * 统一响应体。
 */
public record ApiResponse<T>(int code, String message, T data, String traceId, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, null, Instant.now());
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, null, Instant.now());
    }
}
