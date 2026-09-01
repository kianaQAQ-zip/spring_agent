package com.ecomagent.eval;

import com.ecomagent.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 知识库缺口雷达（未命中问题挖掘）。
 *
 * <p>把检索没召回任何文档的提问捞出来，按出现频次排序——这就是知识库该补的内容清单。
 * 数据全部来自真实对话的 {@code rag_eval} 快照，不需要假数据，也不额外打 LLM。
 *
 * <p>当前按字面聚合（{@code lower(trim(query))}）。要升级成语义聚类，
 * 可对 query 做 embedding 后按余弦距离分组——接口不变，只换聚合实现。
 */
@Service
public class KbGapService {

    private final JdbcTemplate jdbcTemplate;

    public KbGapService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 未命中问题 Top N：按出现次数降序。 */
    public List<Map<String, Object>> topGaps(int days, int limit) {
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
