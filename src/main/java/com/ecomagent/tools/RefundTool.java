package com.ecomagent.tools;

import com.ecomagent.agent.ConfirmationService;
import com.ecomagent.agent.PendingAction;
import com.ecomagent.common.ConfirmRequired;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 退款工具（§2，需人工确认）。
 *
 * <p>标注 {@code @ConfirmRequired}：模型调用本工具时<b>不真正退款</b>，而是经
 * {@code ConfirmationService.request(...)} 落一条 {@code pending_action(status=pending)}，
 * 返回 PENDING_CONFIRMATION 让模型告知用户「已提交，等待坐席确认」。真正执行发生在
 * 坐席 {@code POST /confirm/{id}} 原子确认之后（结果回灌消息流，§2.4）。
 */
@Component
public class RefundTool {

    private final ConfirmationService confirmationService;

    public RefundTool(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @ConfirmRequired(label = "退款")
    @Tool(description = "对指定订单发起退款。该操作涉及资金，需要人工确认后才能执行")
    public String refund(@ToolParam(description = "订单号，形如 ORD-1001") String orderId,
                         @ToolParam(description = "退款原因") String reason,
                         @ToolParam(description = "退款金额（元）") Double amount,
                         ToolContext toolContext) {
        String conversationId = (String) toolContext.getContext().get("conversationId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", orderId);
        params.put("reason", reason);
        params.put("amount", amount);

        PendingAction action = confirmationService.request(conversationId, "refund", params);
        return "{\"status\":\"PENDING_CONFIRMATION\",\"pendingId\":\"" + action.id()
                + "\",\"message\":\"退款申请已提交，等待坐席人工确认\"}";
    }
}
