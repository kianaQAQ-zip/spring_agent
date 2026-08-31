package com.ecomagent.eval;

import java.time.Instant;

/**
 * RAG 线上评估快照（§9 实时指标）。
 *
 * <p>每次真实对话流式结束时采集一条，回答三个问题：
 * <ul>
 *   <li><b>命中率</b>：检索是否返回了文档（{@code docCount > 0}）；</li>
 *   <li><b>引用准确率</b>：回答里的 {@code [n]} 引用有多少越界（编造引用）；</li>
 *   <li><b>成本</b>：回答 token 估算 × 单价。</li>
 * </ul>
 * 全部由真实流量产生，不需要假数据。
 */
public record RagEvalRecord(
        String conversationId,
        String tenantId,
        String platform,
        String query,
        boolean hit,
        int docCount,
        int citationCount,
        int outOfRange,
        int answerTokens,
        double cost,
        Instant createdAt) {
}
