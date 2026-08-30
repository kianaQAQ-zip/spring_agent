package com.ecomagent.api;

import com.ecomagent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 服务入口说明。
 *
 * <p>8080 是<b>纯 API 端口</b>，前端走 Vite dev server（默认 {@code localhost:5173}）。
 * 直接访问 {@code 8080/kb}、{@code 8080/chat} 这类前端路由必然 404——
 * 根路径给一份清单，避免把"走错端口"误判成"后端挂了"。
 */
@RestController
public class RootController {

    @GetMapping("/")
    public ApiResponse<Map<String, Object>> index() {
        return ApiResponse.ok(Map.of(
                "service", "ecom-agent",
                "note", "8080 仅提供 API，前端请访问 Vite dev server（默认 http://localhost:5173）",
                "endpoints", List.of(
                        "GET  /chat/health",
                        "GET  /chat/stream?message=&conversationId=",
                        "POST /kb/upload",
                        "GET  /kb/doc/{docId}",
                        "GET  /kb/doc/{docId}/export?format=",
                        "GET  /confirm/pending?conversationId=",
                        "POST /confirm/{id}"
                )
        ));
    }
}
