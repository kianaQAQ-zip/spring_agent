package com.ecomagent.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import reactor.core.publisher.Flux;

/**
 * 全局异常处理：统一错误响应结构，避免原始堆栈外泄。
 *
 * <p>三类出口：
 * <ul>
 *   <li>静态资源未命中（{@code 8080/kb} 这类误访问）→ 404，不打 ERROR 堆栈；</li>
 *   <li>SSE 端点（{@code produces = text/event-stream}）→ Content-Type 已锁定，
 *       写不了 JSON，改以 SSE {@code error} 事件返回；</li>
 *   <li>其余 → {@link ApiResponse} JSON 500。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 8080 是纯 API（前端走 Vite dev server），静态资源未命中属预期内的 404。
     * 走通用 handler 会被记成 ERROR 500，语义错误且刷屏。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException ex) {
        log.debug("No static resource: {}（8080 仅提供 API，前端请访问 Vite dev server）",
                ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(404, "未找到资源: " + ex.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public Object handle(Exception ex) {
        // 提取可读消息（Spring AI 嵌套异常深层才有真实原因）
        String message = extractReadableMessage(ex);
        log.error("Unhandled exception: {}", message, ex);

        // 判断是否来自 SSE 端点（Content-Type 已设为 text/event-stream）
        // 此时无法写 JSON，只能以 SSE error event 返回
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, message));
    }

    /**
     * 专门处理 SSE 流中的异常——以 SSE error 事件返回，避免 HttpMessageNotWritableException。
     */
    public static Flux<ServerSentEvent<String>> toSseError(Throwable ex) {
        String message = extractReadableMessage(ex);
        log.error("SSE stream error: {}", message, ex);

        String json;
        try {
            json = objectMapper.writeValueAsString(ApiResponse.fail(500, message));
        } catch (JsonProcessingException e) {
            json = "{\"code\":500,\"message\":\"" + message.replace("\"", "'") + "\"}";
        }

        return Flux.just(ServerSentEvent.<String>builder()
                .event("error")
                .data(json)
                .build());
    }

    private static String extractReadableMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = ex.getClass().getSimpleName();
        }
        // 截断超长消息（防止 API 错误体整个暴露）
        if (msg.length() > 500) {
            msg = msg.substring(0, 500) + "...";
        }
        return msg;
    }
}
