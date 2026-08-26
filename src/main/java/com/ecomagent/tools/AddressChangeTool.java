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
 * 改地址工具（§2，需人工确认）。
 */
@Component
public class AddressChangeTool {

    private final ConfirmationService confirmationService;

    public AddressChangeTool(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @ConfirmRequired(label = "修改收货地址")
    @Tool(description = "修改订单的收货地址。该操作影响履约，需要人工确认后才能执行")
    public String changeAddress(@ToolParam(description = "订单号，形如 ORD-1001") String orderId,
                                @ToolParam(description = "新的收货地址") String newAddress,
                                ToolContext toolContext) {
        String conversationId = (String) toolContext.getContext().get("conversationId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", orderId);
        params.put("newAddress", newAddress);

        PendingAction action = confirmationService.request(conversationId, "changeAddress", params);
        return "{\"status\":\"PENDING_CONFIRMATION\",\"pendingId\":\"" + action.id()
                + "\",\"message\":\"改地址申请已提交，等待坐席人工确认\"}";
    }
}
