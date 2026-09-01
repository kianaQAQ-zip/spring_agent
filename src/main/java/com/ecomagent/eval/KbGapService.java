package com.ecomagent.eval;

import com.ecomagent.common.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 知识库缺口雷达（未命中问题挖掘）。
 *
 * <p>把检索没召回任何文档的提问捞出来，聚成"该补哪些文档"的清单。
 * 数据全部来自真实对话的 {@code rag_eval} 快照，不需要假数据，也不额外打 LLM。
 *
 * <p>聚合策略两级：
 * <ol>
 *   <li><b>语义聚类</b>（{@link GapClusterService}）：对 query 做 embedding 后按余弦距离分组，
 *       把"7天无理由""过了7天还能退吗"聚成一类；</li>
 *   <li><b>字面聚合</b>：embedding API 不可用（额度耗尽/断网）时降级，按 {@code lower(trim(query))} 分组。</li>
 * </ol>
 */
@Service
public class KbGapService {

    private static final Logger log = LoggerFactory.getLogger(KbGapService.class);

    private final JdbcTemplate jdbcTemplate;
    private final GapClusterService gapClusterService;

    public KbGapService(JdbcTemplate jdbcTemplate, GapClusterService gapClusterService) {
        this.jdbcTemplate = jdbcTemplate;
        this.gapClusterService = gapClusterService;
    }

    /** 未命中问题 Top N：优先语义聚类，降级字面聚合。 */
    public List<Map<String, Object>> topGaps(int days, int limit) {
        try {
            return gapClusterService.cluster(days, limit);
        } catch (Exception e) {
            log.warn("语义聚类失败，回退字面聚合: {}", e.getMessage());
            return literalTopGaps(days, limit);
        }
    }

    private List<Map<String, Object>> literalTopGaps(int days, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT lower(trim(query))                    AS query,
                               count(*)                              AS times,
                               max(created_at)                       AS last_seen,
                               count(DISTINCT platform)              AS platforms
                        FROM rag_eval
                        WHERE tenant_id = ?
                          AND hit = false
                          AND query IS NOT NULL
                          AND trim(query) <> ''
                          AND created_at >= now() - (? * interval '1 day')
                        GROUP BY 1
                        ORDER BY times DESC, last_seen DESC
                        LIMIT ?
                        """,
                TenantContext.get(), Math.min(Math.max(days, 1), 180),
                Math.min(Math.max(limit, 1), 100));
    }

    /** 缺口总览：未命中对话数、不同问法数、未命中率。 */
    public Map<String, Object> gapSummary(int days) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT count(*)                                              AS total,
                               count(*) FILTER (WHERE NOT hit)                        AS missed,
                               count(DISTINCT CASE WHEN NOT hit THEN lower(trim(query)) END) AS distinct_missed
                        FROM rag_eval
                        WHERE tenant_id = ?
                          AND created_at >= now() - (? * interval '1 day')
                        """, TenantContext.get(), Math.min(Math.max(days, 1), 180));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }
}
