package com.ecomagent.api;

import com.ecomagent.common.ApiResponse;
import com.ecomagent.handoff.HandoffService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 转人工工单接口（M3）：坐席受理 Agent 搞不定的会话，带着上下文接手。
 */
@RestController
@RequestMapping("/handoff")
public class HandoffController {

    private final HandoffService handoffService;

    public HandoffController(HandoffService handoffService) {
        this.handoffService = handoffService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(handoffService.list(status));
    }

    @PostMapping("/{id}/claim")
    public ApiResponse<Boolean> claim(@PathVariable String id,
                                      @RequestParam(defaultValue = "agent") String operator) {
        return ApiResponse.ok(handoffService.claim(id, operator));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<Boolean> close(@PathVariable String id,
                                      @RequestParam(defaultValue = "agent") String operator) {
        return ApiResponse.ok(handoffService.close(id, operator));
    }
}
