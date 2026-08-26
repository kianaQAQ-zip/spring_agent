package com.ecomagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 只读工具：订单 / 物流查询（§2「只读直执行」，不落 pending）。
 *
 * <p>演示用 mock 数据，真实项目替换为订单中心 / 物流供应商 API。
 * 未标注 {@code @ConfirmRequired}，Tool 调用时直接执行。
 */
@Component
public class OrderQueryTool {

    @Tool(description = "根据订单号查询订单状态与最新物流轨迹（只读，无需人工确认）")
    public String queryOrder(@ToolParam(description = "订单号，形如 ORD-1001") String orderId) {
        return "{\"tool\":\"queryOrder\",\"orderId\":\"" + orderId
                + "\",\"status\":\"SHIPPED\",\"logistics\":{\"carrier\":\"顺丰速运\","
                + "\"trackingNo\":\"SF1234567890\",\"latest\":\"已到达杭州转运中心\"}}";
    }
}
