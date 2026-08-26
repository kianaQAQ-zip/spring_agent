package com.ecomagent.agent;

import java.time.Instant;

/**
 * L2 结构化会话状态（§8 Layer 2）。
 *
 * <p>承载当前对话的意图 / 订单号 / 情绪，随对话增量更新，供 QueryRewrite 与 ContextAssembler 使用。
 * 不存废话摘要，只存「任务推进所需的最小结构化状态」。
 */
public record SessionState(
        String sessionId,
        String tenantId,
        String intent,
        String orderId,
        String emotion,
        long version,
        Instant updatedAt) {

    public static SessionState empty(String sessionId, String tenantId) {
        return new SessionState(sessionId, tenantId, null, null, null, 0, Instant.now());
    }

    public boolean isIntent(String intent) {
        return intent != null && intent.equalsIgnoreCase(intent);
    }
}
