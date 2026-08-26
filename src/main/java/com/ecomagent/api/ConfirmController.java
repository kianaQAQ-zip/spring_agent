package com.ecomagent.api;

import com.ecomagent.agent.ConfirmationConflictException;
import com.ecomagent.agent.ConfirmationService;
import com.ecomagent.agent.PendingAction;
import com.ecomagent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 坐席确认台接口（§2，M8 前端对接，M4 先验证后端契约）。
 *
 * <p>动作：
 * <ul>
 *   <li>{@code POST /confirm/{id}} 确认执行；</li>
 *   <li>{@code PUT  /confirm/{id}} 改参后确认；</li>
 *   <li>{@code POST /confirm/{id}/reject} 驳回；</li>
 *   <li>{@code POST /confirm/{id}/cancel} 取消；</li>
 *   <li>{@code GET  /confirm/{id}} 查询单条；</li>
 *   <li>{@code GET  /confirm/pending} 按会话列待确认。</li>
 * </ul>
 */
@RestController
@RequestMapping("/confirm")
public class ConfirmController {

    private final ConfirmationService confirmationService;

    public ConfirmController(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @PostMapping("/{id}")
    public ApiResponse<PendingAction> confirm(@PathVariable String id,
                                              @RequestBody(required = false) ConfirmRequest body) {
        Map<String, Object> params = body == null ? null : body.params();
        String operator = body == null ? null : body.operator();
        try {
            return ApiResponse.ok(confirmationService.confirm(id, params, operator));
        } catch (ConfirmationConflictException e) {
            return ApiResponse.fail(409, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<PendingAction> modifyAndConfirm(@PathVariable String id,
                                                       @RequestBody ConfirmRequest body) {
        Map<String, Object> params = body == null ? null : body.params();
        String operator = body == null ? null : body.operator();
        try {
            return ApiResponse.ok(confirmationService.confirm(id, params, operator));
        } catch (ConfirmationConflictException e) {
            return ApiResponse.fail(409, e.getMessage());
        }
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<PendingAction> reject(@PathVariable String id,
                                             @RequestBody(required = false) ConfirmRequest body) {
        String operator = body == null ? null : body.operator();
        try {
            return ApiResponse.ok(confirmationService.reject(id, operator));
        } catch (ConfirmationConflictException e) {
            return ApiResponse.fail(409, e.getMessage());
        }
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<PendingAction> cancel(@PathVariable String id) {
        try {
            return ApiResponse.ok(confirmationService.cancel(id));
        } catch (ConfirmationConflictException e) {
            return ApiResponse.fail(409, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<PendingAction> get(@PathVariable String id) {
        PendingAction action = confirmationService.get(id);
        return action == null ? ApiResponse.fail(404, "pending action not found: " + id)
                : ApiResponse.ok(action);
    }

    @GetMapping("/pending")
    public ApiResponse<List<PendingAction>> listPending(@RequestParam("conversationId") String conversationId) {
        return ApiResponse.ok(confirmationService.listByConversation(conversationId));
    }

    /** 确认/驳回请求体：params 为改参后的最终参数，operator 为坐席标识。 */
    public record ConfirmRequest(Map<String, Object> params, String operator) {
    }
}
