package com.ecomagent.tools;

import com.ecomagent.common.PiiMaskUtil;
import com.ecomagent.common.Platform;
import com.ecomagent.common.TenantContext;
import com.ecomagent.order.OrderRecord;
import com.ecomagent.order.OrderRepository;
import com.ecomagent.order.TraceNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读工具：订单 / 物流查询（§2「只读直执行」，不落 pending）。
 *
 * <p>接真实订单库（{@code orders} + {@code order_trace}），取代此前的 mock 实现。
 * 未标注 {@code @ConfirmRequired}，Tool 调用时直接执行。
 *
 * <p>两个刻意的设计：
 * <ul>
 *   <li><b>查不到必须明确返回 found=false</b>——否则 LLM 会顺着上下文编造一个物流状态出来；</li>
 *   <li><b>手机号 / 姓名出库即脱敏</b>——这是给 LLM 看的内容，可能进日志与回答。</li>
 * </ul>
 */
@Component
public class OrderQueryTool {

    private static final int TRACE_LIMIT = 5;

    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    public OrderQueryTool(OrderRepository orderRepository, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "根据订单号查询订单状态与最新物流轨迹（只读，无需人工确认）。"
            + "查不到时会返回 found=false，此时应如实告知用户未找到，不要编造订单信息。")
    public String queryOrder(@ToolParam(description = "订单号，形如 ORD-1001") String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return toJson(Map.of("found", false, "message", "未提供订单号，请先向用户索取"));
        }

        String tenantId = TenantContext.get();
        OrderRecord o = orderRepository.findByOrderId(tenantId, orderId.trim());
        if (o == null) {
            return toJson(Map.of(
                    "found", false,
                    "orderId", orderId,
                    "message", "未找到该订单。请核对订单号，并确认是否选对了平台渠道"
                            + "（不同平台订单号不通用）。不要编造物流信息。"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("orderId", o.orderId());
        result.put("platform", Platform.of(o.platform()).label());
        result.put("status", o.status());
        result.put("amount", o.amount());
        result.put("itemTitle", o.itemTitle());
        result.put("quantity", o.quantity());
        result.put("buyerName", maskName(o.buyerName()));
        result.put("buyerPhone", PiiMaskUtil.mask(o.buyerPhone()));
        result.put("address", o.address());
        result.put("createdAt", o.createdAt().toString());

        if (o.hasLogistics()) {
            List<TraceNode> trace = orderRepository.findTrace(tenantId, o.orderId(), TRACE_LIMIT);
            List<String> nodes = trace.stream().map(TraceNode::node).toList();
            result.put("logistics", Map.of(
                    "carrier", o.carrier(),
                    "trackingNo", o.trackingNo(),
                    "latest", o.latestTrace() == null ? "" : o.latestTrace(),
                    "trace", nodes.isEmpty() ? List.of(o.latestTrace()) : nodes));
        } else {
            result.put("logistics", Map.of("carrier", "", "trackingNo", "", "latest", "尚未发货"));
        }
        return toJson(result);
    }

    /** 姓名脱敏：保留姓氏。两字及以上取首字，单字直接星号。 */
    private static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String n = name.trim();
        return n.length() <= 1 ? "*" : n.substring(0, 1) + "*".repeat(n.length() - 1);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            // 序列化失败也不能让 Tool 抛异常——否则整条链路 500
            return "{\"found\":false,\"message\":\"订单信息序列化失败，请稍后重试\"}";
        }
    }
}
