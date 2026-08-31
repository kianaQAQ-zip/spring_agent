package com.ecomagent.eval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * RAG 评估快照持久化。
 */
@Repository
public class RagEvalRepository {

    private final JdbcTemplate jdbcTemplate;

    public RagEvalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(RagEvalRecord r) {
        jdbcTemplate.update("""
                        INSERT INTO rag_eval
                            (id, conversation_id, tenant_id, platform, query, hit, doc_count,
                             citation_count, out_of_range, answer_tokens, cost)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                java.util.UUID.randomUUID().toString(),
                r.conversationId(), r.tenantId(), r.platform(), r.query(), r.hit(),
                r.docCount(), r.citationCount(), r.outOfRange(), r.answerTokens(), r.cost());
    }

    /** 汇总：总对话数 / 命中率 / 平均引用越界数 / 总成本。 */
    public Map<String, Object> summary(String tenantId) {
        return jdbcTemplate.queryForMap("""
                        SELECT count(*)                                              AS total,
                               round(avg(CASE WHEN hit THEN 1.0 ELSE 0.0 END), 4)   AS hit_rate,
                               round(avg(out_of_range), 4)                           AS avg_out_of_range,
                               round(avg(citation_count), 4)                         AS avg_citations,
                               coalesce(sum(cost), 0)                                AS total_cost,
                               coalesce(sum(answer_tokens), 0)                       AS total_tokens
                        FROM rag_eval
                        WHERE tenant_id = ?
                        """, tenantId);
    }

    /** 成本/对话量按天趋势（近 N 天）。 */
    public List<Map<String, Object>> dailyTrend(String tenantId, int days) {
        return jdbcTemplate.queryForList("""
                        SELECT to_char(created_at, 'YYYY-MM-DD') AS day,
                               count(*)                           AS conversations,
                               coalesce(sum(cost), 0)             AS cost,
                               round(avg(CASE WHEN hit THEN 1.0 ELSE 0.0 END), 4) AS hit_rate
                        FROM rag_eval
                        WHERE tenant_id = ? AND created_at >= now() - (? * interval '1 day')
                        GROUP BY 1
                        ORDER BY 1
                        """, tenantId, days);
    }

    public Instant latestTime(String tenantId) {
        List<Timestamp> list = jdbcTemplate.query(
                "SELECT max(created_at) FROM rag_eval WHERE tenant_id = ?",
                (rs, i) -> rs.getTimestamp(1), tenantId);
        if (list.isEmpty() || list.get(0) == null) {
            return Instant.EPOCH;
        }
        return list.get(0).toInstant();
    }
}
