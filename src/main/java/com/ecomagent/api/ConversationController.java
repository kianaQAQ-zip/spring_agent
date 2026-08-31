package com.ecomagent.api;

import com.ecomagent.common.ApiResponse;
import com.ecomagent.conversation.ConversationQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 历史会话接口（L2）：检索、筛选、回溯。
 */
@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationQueryService queryService;

    public ConversationController(ConversationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryService.list(platform, keyword, from, to,
                Math.max(page, 1), Math.min(Math.max(size, 1), 100)));
    }

    @GetMapping("/{conversationId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String conversationId) {
        return ApiResponse.ok(queryService.detail(conversationId));
    }
}
