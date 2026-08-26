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
 * 优惠券发放工具（§2，需人工确认）。
 */
@Component
public class CouponTool {

    private final ConfirmationService confirmationService;

    public CouponTool(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @ConfirmRequired(label = "发放优惠券")
    @Tool(description = "向用户发放优惠券。涉及营销成本，需要人工确认后才能执行")
    public String issueCoupon(@ToolParam(description = "优惠券类型，如 full-reduction / cash-back") String couponType,
                              @ToolParam(description = "券面金额（元）") Double value,
                              ToolContext toolContext) {
        String conversationId = (String) toolContext.getContext().get("conversationId");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("couponType", couponType);
        params.put("value", value);

        PendingAction action = confirmationService.request(conversationId, "issueCoupon", params);
        return "{\"status\":\"PENDING_CONFIRMATION\",\"pendingId\":\"" + action.id()
                + "\",\"message\":\"优惠券发放申请已提交，等待坐席人工确认\"}";
    }
}
