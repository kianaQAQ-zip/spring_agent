package com.ecomagent.eval;

import com.ecomagent.common.TenantContext;
import com.ecomagent.rag.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 未命中问题语义聚类（M4）：把字面上不同但语义相同的提问聚成一类。
 *
 * <p>流程：取未命中 query → embedding 编码 → 贪心聚类（余弦相似度 &ge; 阈值归入同簇，
 * 纯算法见 {@link GapClustering}）→ 落 {@code rag_gap_cluster} 表 → 返回按成员数降序的簇。
 *
 * <p>这样「7天无理由」「过了7天还能退吗」「七天退货」会被聚成同一类，
 * 而不是字面聚合里的三个独立条目——缺口雷达真正反映"同一类知识盲区"。
 *
 * <p>依赖 embedding API，失败时由 {@link KbGapService} 回退到字面聚合。
 * 单商家场景未命中量小（几十条），每次全量重聚可接受。
 */
@Service
public class GapClusterService {

    private static final Logger log = LoggerFactory.getLogger(GapClusterService.class);
    private static final double THRESHOLD = 0.82;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    public GapClusterService(JdbcTemplate jdbcTemplate, EmbeddingService embeddingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
    }

    /** 聚类未命中问题，返回按频次降序的簇（每簇：代表问题 + 成员数）。 */
    public List<Map<String, Object>> cluster(int days, int limit) {
        List<String> queries = fetchMissedQueries(days);
        if (queries.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = embeddingService.embed(queries);

        List<GapClustering.Cluster> clusters =
                GapClustering.cluster(queries, vectors, THRESHOLD);
        persist(clusters);

        return clusters.stream()
                .sorted(Comparator.comparingInt(GapClustering.Cluster::memberCount).reversed())
                .limit(limit)
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("query", c.representative());
                    m.put("times", c.memberCount());
                    return m;
                })
                .toList();
    }

    /** 未命中 query（去重、去空白）。 */
    private List<String> fetchMissedQueries(int days) {
        return jdbcTemplate.queryForList("""
                        SELECT DISTINCT lower(trim(query)) AS q
                        FROM rag_eval
                        WHERE tenant_id = ?
                          AND hit = false
                          AND query IS NOT NULL
                          AND trim(query) <> ''
                          AND created_at >= now() - (? * interval '1 day')
                        """, TenantContext.get(), days)
                .stream()
                .map(r -> String.valueOf(r.get("q")))
                .toList();
    }

    /** 落库：清空该租户旧簇，重建当前结果（单商家量小，全量重建可接受）。 */
    private void persist(List<GapClustering.Cluster> clusters) {
        try {
            String tenantId = TenantContext.get();
            jdbcTemplate.update("DELETE FROM rag_gap_cluster WHERE tenant_id = ?", tenantId);
            for (GapClustering.Cluster c : clusters) {
                jdbcTemplate.update("""
                                INSERT INTO rag_gap_cluster
                                    (id, tenant_id, representative, member_count, last_seen)
                                VALUES (?, ?, ?, ?, now())
                                """,
                        java.util.UUID.randomUUID().toString(), tenantId,
                        c.representative(), c.memberCount());
            }
        } catch (Exception e) {
            // 聚类结果落库失败不影响返回内存结果（缺口雷达仍可用）
            log.warn("语义簇落库失败: {}", e.getMessage());
        }
    }
}
