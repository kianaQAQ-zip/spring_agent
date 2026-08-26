package com.ecomagent.agent;

import java.time.Instant;

/**
 * 待确认动作（§2 对应 {@code pending_action} 表）。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code paramsJson}：原始入参（发起时由工具归一化）；</li>
 *   <li>{@code finalParamsJson}：坐席改参后的最终参数；</li>
 *   <li>{@code result}：确认执行后的结果（回灌消息流，§2.4）；</li>
 *   <li>{@code idempotencyKey}：幂等键 = hash(conversation_id + tool + 归一化 params)。</li>
 * </ul>
 */
public record PendingAction(
        String id,
        String conversationId,
        String tenantId,
        String tool,
        String paramsJson,
        String status,
        String idempotencyKey,
        String finalParamsJson,
        String operator,
        Instant executedAt,
        String result,
        Instant createdAt,
        Instant expiresAt) {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_EXPIRED = "expired";
}
