package com.ecomagent.eval;

/**
 * Agent 对单条查询的回答（评估用）：意图 / 文本 / 是否带引用 / 是否忠实。
 */
public record AgentAnswer(String intent, String text, boolean cited, boolean faithful) {
}
