package com.ecomagent.conversation;

import java.time.Instant;

/**
 * 会话视图（只读投影）。
 *
 * <p>{@code conversationId} 是业务键（前端生成并随请求透传），
 * 与表内主键 {@code id}（UUID）区分，避免把主键语义泄漏到上层。
 */
public record ConversationRecord(
        String conversationId,
        String tenantId,
        String platform,
        String title,
        Instant createdAt,
        Instant updatedAt) {
}
