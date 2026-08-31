package com.ecomagent.conversation;

import java.time.Instant;

/**
 * 消息视图（只读投影）。
 *
 * <p>{@code content} 在写入前已由调用方完成 PII 脱敏（§5 双重脱敏之"入库脱敏"），
 * {@code piiMasked} 标记该条是否经过掩码，供导出时追溯。
 */
public record MessageRecord(
        String conversationId,
        String tenantId,
        String platform,
        String role,
        String content,
        boolean piiMasked,
        Instant createdAt) {
}
