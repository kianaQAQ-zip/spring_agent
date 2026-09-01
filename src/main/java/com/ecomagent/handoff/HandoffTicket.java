package com.ecomagent.handoff;

import java.time.Instant;

/**
 * 转人工工单。
 *
 * <p>触发原因（{@link Reason}）决定工单优先级与交接话术。
 * {@code context} 为对话历史快照 JSON——工单可能几小时后才被处理，
 * 那时会话记录可能已被清理，所以上下文必须随工单一起固化。
 */
public record HandoffTicket(
        String id,
        String conversationId,
        String tenantId,
        String platform,
        String reason,
        String detail,
        String context,
        String status,
        String operator,
        Instant createdAt,
        Instant claimedAt,
        Instant closedAt) {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_CLAIMED = "claimed";
    public static final String STATUS_CLOSED = "closed";

    /** 触发原因：优先级由高到低。 */
    public enum Reason {
        /** 用户明确要求人工 */
        USER_REQUEST("用户要求转人工"),
        /** 情绪负面（投诉/愤怒） */
        NEGATIVE_EMOTION("用户情绪负面"),
        /** 事实一致性裁判判定回答不可靠 */
        GUARDRAIL_FAIL("回答未通过事实校验"),
        /** 检索未命中——知识盲区 */
        NO_HIT("检索未命中，知识盲区");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
