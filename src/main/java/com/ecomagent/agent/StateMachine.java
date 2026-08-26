package com.ecomagent.agent;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 意图状态机 + 主动澄清（§8.6）。
 *
 * <p>基于当前会话状态与新一轮信号，决定继续推进还是主动澄清：
 * 多订单口述冲突、意图不兼容跳转、代词指代但无订单号 → {@code needs_clarification}。
 */
@Component
public class StateMachine {

    private static final Set<String> FROM_ORDER_QUERY = Set.of("REFUND", "ADDRESS_CHANGE", "COUPON");
    private static final Set<String> FROM_KNOWLEDGE_QA =
            Set.of("ORDER_QUERY", "REFUND", "ADDRESS_CHANGE", "COUPON");

    public Decision decide(String currentIntent, String currentOrderId, Signal signal) {
        // 1. 多订单口述冲突
        if (signal.orderIdCount() > 1) {
            return Decision.clarify("检测到多个订单号，请确认要操作哪一个");
        }
        // 2. 代词指代但无可用订单号
        if (signal.hasPronoun() && signal.orderId() == null && isBlank(currentOrderId)) {
            return Decision.clarify("请提供要操作的订单号");
        }
        // 3. 意图不兼容跳转
        if (currentIntent != null && signal.intent() != null
                && signal.intent() != Intent.UNKNOWN
                && !currentIntent.equalsIgnoreCase(signal.intent().name())
                && !canTransition(currentIntent, signal.intent().name())) {
            return Decision.clarify("检测到意图变化，请确认本次要执行的操作");
        }
        return Decision.proceed();
    }

    private boolean canTransition(String from, String to) {
        String f = from == null ? "" : from.toUpperCase();
        String t = to == null ? "" : to.toUpperCase();
        if (f.equals(t)) {
            return true;
        }
        if ("ORDER_QUERY".equals(f)) {
            return FROM_ORDER_QUERY.contains(t);
        }
        if ("KNOWLEDGE_QA".equals(f)) {
            return FROM_KNOWLEDGE_QA.contains(t);
        }
        // 其余意图（REFUND/ADDRESS_CHANGE/COUPON）允许回到订单查询或知识问答
        return "ORDER_QUERY".equals(t) || "KNOWLEDGE_QA".equals(t);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
