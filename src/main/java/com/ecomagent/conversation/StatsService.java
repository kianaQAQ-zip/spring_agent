package com.ecomagent.conversation;

import com.ecomagent.common.Platform;
import com.ecomagent.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计聚合（L1 数据服务）：把落库的会话/消息/状态聚成看板指标。
 *
 * <p>只做 SELECT 聚合，不写库。所有查询强制带 {@code tenant_id}。
 * 返回 {@code List<Map>} 直接序列化为 JSON，避免为每个指标建 DTO。
 */
@Service
public class StatsService {

    private final JdbcTemplate jdbcTemplate;

    public StatsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 综合概览：会话数 / 消息数 / 覆盖平台数。 */
    public Map<String, Object> overview() {
        String tenantId = TenantContext.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conversations", jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation WHERE tenant_id = ?", Integer.class, tenantId));
        m.put("messages", jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message WHERE tenant_id = ?", Integer.class, tenantId));
        m.put("platforms", jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT platform) FROM conversation WHERE tenant_id = ?", Integer.class, tenantId));
        return m;
    }

    /** 咨询量按天趋势（近 N 天）。 */
    public List<Map<String, Object>> trend(int days) {
        return jdbcTemplate.queryForList("""
                        SELECT to_char(created_at, 'YYYY-MM-DD') AS day,
                               count(*)                            AS count
                        FROM conversation
                        WHERE tenant_id = ? AND created_at >= now() - (? * interval '1 day')
                        GROUP BY 1 ORDER BY 1
                        """, TenantContext.get(), days);
    }

    /** 平台分布（会话数按平台）。 */
    public List<Map<String, Object>> platformDist() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT platform, count(*) AS count
                        FROM conversation
                        WHERE tenant_id = ?
                        GROUP BY platform ORDER BY count DESC
                        """, TenantContext.get());
        // 平台 code → 中文 label，前端展示更友好
        for (Map<String, Object> r : rows) {
            r.put("label", Platform.of(String.valueOf(r.get("platform"))).label());
        }
        return rows;
    }

    /** 时段分布（消息数按小时，0-23）。 */
    public List<Map<String, Object>> hourlyDist() {
        return jdbcTemplate.queryForList("""
                        SELECT extract(hour from created_at)::int AS hour,
                               count(*)                            AS count
                        FROM message
                        WHERE tenant_id = ?
                        GROUP BY 1 ORDER BY 1
                        """, TenantContext.get());
    }

    /** 意图分布（从 session_state.state_json 聚合，空意图剔除）。 */
    public List<Map<String, Object>> intentDist() {
        return jdbcTemplate.queryForList("""
                        SELECT state_json::jsonb ->> 'intent' AS intent,
                               count(*)                      AS count
                        FROM session_state
                        WHERE tenant_id = ?
                          AND state_json::jsonb ->> 'intent' IS NOT NULL
                          AND state_json::jsonb ->> 'intent' <> ''
                        GROUP BY 1 ORDER BY count DESC
                        """, TenantContext.get());
    }
}
