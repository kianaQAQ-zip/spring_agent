package com.ecomagent.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 会话状态增量（§8 Layer 2 增量提取，非废话摘要）。
 *
 * <p>由 qwen-turbo + {@code BeanOutputConverter} 结构化输出；仅非空字段覆盖当前状态，
 * 空字段表示「无变化」。{@code noChange=true} 表示本轮无需更新状态。
 */
public record SessionStateDelta(
        @JsonProperty("intent") String intent,
        @JsonProperty("orderId") String orderId,
        @JsonProperty("emotion") String emotion,
        @JsonProperty("noChange") Boolean noChange) {

    public boolean isNoChange() {
        return Boolean.TRUE.equals(noChange);
    }
}
