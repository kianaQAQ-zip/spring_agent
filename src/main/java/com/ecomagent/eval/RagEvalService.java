package com.ecomagent.eval;

import com.ecomagent.common.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RAG 线上评估编排：采集 + 聚合查询。
 *
 * <p>采集在 {@code ChatService} 流式结束时调用（旁路，失败不阻断对话，仅告警）。
 * 聚合供评估台页面读。
 */
@Service
public class RagEvalService {

    private final RagEvalRepository repository;

    public RagEvalService(RagEvalRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一轮对话的 RAG 指标。
     *
     * @param query         用户原始提问
     * @param tenantId      显式传入（SSE 回调在 Reactor 线程，ThreadLocal 不可用）
     * @param platform      同上
     * @param docCount      检索返回文档数（0 = 未命中）
     * @param citationCount 回答中引用标总数
     * @param outOfRange    越界（编造）引用数
     * @param answerTokens  回答 token 估算
     * @param cost          本轮成本估算
     */
    public void record(String conversationId, String query, String tenantId, String platform,
                       int docCount, int citationCount, int outOfRange, int answerTokens,
                       double cost) {
        try {
            repository.insert(new RagEvalRecord(
                    conversationId, tenantId, platform, query,
                    docCount > 0, docCount, citationCount, outOfRange, answerTokens, cost,
                    java.time.Instant.now()));
        } catch (Exception e) {
            // 评估采集是旁路，不能因它失败影响对话
            org.slf4j.LoggerFactory.getLogger(RagEvalService.class)
                    .warn("rag_eval 采集失败: {}", e.getMessage());
        }
    }

    public Map<String, Object> summary() {
        return repository.summary(TenantContext.get());
    }

    public List<Map<String, Object>> dailyTrend(int days) {
        return repository.dailyTrend(TenantContext.get(), days);
    }
}
