package com.ecomagent.order;

import com.ecomagent.common.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单写操作执行器（M4）：坐席确认后真实落库，取代 mock。
 *
 * <p>三个动作都走「确认护栏」：模型发起 → pending → 坐席确认 → 此处执行。
 * 本组件只负责真正改数据，不碰 pending 生命周期（那是 {@code ConfirmationService} 的事）。
 *
 * <p>关键约束：
 * <ul>
 *   <li><b>退款</b>：订单存在才改状态为 REFUNDING；不存在返回 NOT_FOUND 让坐席知道；</li>
 *   <li><b>改地址</b>：仅发货前（PENDING/PAID）可改，已发货改不了（履约约束）；</li>
 *   <li><b>发券</b>：插入 coupon 表，issued 状态，记录发放人。</li>
 * </ul>
 */
@Component
public class OrderActionExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OrderActionExecutor(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 按 tool 分派。返回 JSON 结果，供 §2.4 回灌消息流。 */
    public String execute(String tool, String paramsJson, String operator) {
        Map<String, Object> p = parse(paramsJson);
        return switch (tool) {
            case "refund" -> refund(p, operator);
            case "changeAddress" -> changeAddress(p, operator);
            case "issueCoupon" -> issueCoupon(p, operator);
            default -> throw new IllegalArgumentException("unknown tool: " + tool);
        };
    }

    private String refund(Map<String, Object> p, String operator) {
        String orderId = str(p.get("orderId"));
        String reason = str(p.get("reason"));
        String tenantId = TenantContext.get();

        int rows = jdbcTemplate.update(
                "UPDATE orders SET status = 'REFUNDING', updated_at = now() "
                        + "WHERE tenant_id = ? AND order_id = ?",
                tenantId, orderId);
        if (rows == 0) {
            return toJson(Map.of("status", "NOT_FOUND", "orderId", orderId,
                    "message", "订单不存在或不属于当前租户，请核对订单号"));
        }
        return toJson(Map.of("status", "REFUNDING", "orderId", orderId,
                "reason", reason == null ? "" : reason, "operator", op(operator)));
    }

    private String changeAddress(Map<String, Object> p, String operator) {
        String orderId = str(p.get("orderId"));
        String newAddress = str(p.get("newAddress"));
        String tenantId = TenantContext.get();

        // 履约约束：仅发货前（PENDING/PAID）可改地址
        int rows = jdbcTemplate.update(
                "UPDATE orders SET address = ?, updated_at = now() "
                        + "WHERE tenant_id = ? AND order_id = ? AND status IN ('PENDING', 'PAID')",
                newAddress, tenantId, orderId);
        if (rows == 0) {
            return toJson(Map.of("status", "NOT_CHANGEABLE", "orderId", orderId,
                    "message", "订单已发货或不存在，无法修改收货地址"));
        }
        return toJson(Map.of("status", "ADDRESS_CHANGED", "orderId", orderId,
                "newAddress", newAddress == null ? "" : newAddress, "operator", op(operator)));
    }

    private String issueCoupon(Map<String, Object> p, String operator) {
        String type = str(p.get("couponType"));
        double value = num(p.get("value"));
        String tenantId = TenantContext.get();
        String id = java.util.UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT INTO coupon (id, tenant_id, coupon_type, value, status, issued_by) "
                        + "VALUES (?, ?, ?, ?, 'issued', ?)",
                id, tenantId, type == null ? "unknown" : type, value, op(operator));
        return toJson(Map.of("status", "ISSUED", "couponId", id, "couponType", type,
                "value", value, "operator", op(operator)));
    }

    private Map<String, Object> parse(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = objectMapper.readValue(json, Map.class);
            return m == null ? Map.of() : m;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{\"status\":\"ERROR\",\"message\":\"结果序列化失败\"}";
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String op(String operator) {
        return operator == null ? "" : operator;
    }
}
