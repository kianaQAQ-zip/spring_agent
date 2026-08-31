package com.ecomagent.conversation;

import com.ecomagent.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史会话检索（L1 数据服务）：分页 + 多维筛选 + 详情回溯。
 *
 * <p>筛选条件：平台 / 关键词（命中 message.content）/ 时间范围。
 * 关键词用 {@code ILIKE}，参数绑定，防注入。
 */
@Service
public class ConversationQueryService {

    private final JdbcTemplate jdbcTemplate;

    public ConversationQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 分页列会话，附带消息数与最近活跃时间。
     *
     * @param keyword 匹配消息内容，null/blank 表示不限
     */
    public Map<String, Object> list(String platform, String keyword,
                                    String from, String to, int page, int size) {
        String tenantId = TenantContext.get();
        StringBuilder where = new StringBuilder(" WHERE c.tenant_id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);

        if (platform != null && !platform.isBlank() && !"unknown".equals(platform)) {
            where.append(" AND c.platform = ? ");
            args.add(platform);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM message m2 "
                    + "WHERE m2.conversation_id = c.conversation_id AND m2.tenant_id = c.tenant_id "
                    + "AND m2.content ILIKE ?) ");
            args.add("%" + keyword + "%");
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND c.created_at >= ?::timestamptz ");
            args.add(from);
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND c.created_at <= ?::timestamptz ");
            args.add(to);
        }

        long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM conversation c" + where, Long.class, args.toArray());

        String sql = """
                SELECT c.conversation_id, c.platform, c.title, c.created_at, c.updated_at,
                       (SELECT count(*) FROM message m WHERE m.conversation_id = c.conversation_id
                        AND m.tenant_id = c.tenant_id) AS msg_count
                FROM conversation c
                """ + where + """
                ORDER BY c.updated_at DESC
                LIMIT ? OFFSET ?
                """;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);

        List<Map<String, Object>> rows = jdbcTemplate.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conversationId", rs.getString("conversation_id"));
            m.put("platform", rs.getString("platform"));
            m.put("title", rs.getString("title"));
            m.put("createdAt", toInstant(rs.getTimestamp("created_at")).toString());
            m.put("updatedAt", toInstant(rs.getTimestamp("updated_at")).toString());
            m.put("messageCount", rs.getInt("msg_count"));
            return m;
        }, pageArgs.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("items", rows);
        return result;
    }

    /** 会话详情：全部消息按时间正序。 */
    public Map<String, Object> detail(String conversationId) {
        String tenantId = TenantContext.get();
        List<Map<String, Object>> messages = jdbcTemplate.query("""
                        SELECT role, content, pii_masked, platform, created_at
                        FROM message
                        WHERE tenant_id = ? AND conversation_id = ?
                        ORDER BY created_at ASC
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", rs.getString("role"));
                    m.put("content", rs.getString("content"));
                    m.put("piiMasked", rs.getBoolean("pii_masked"));
                    m.put("platform", rs.getString("platform"));
                    m.put("createdAt", toInstant(rs.getTimestamp("created_at")).toString());
                    return m;
                }, tenantId, conversationId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId);
        result.put("messages", messages);
        return result;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? Instant.EPOCH : ts.toInstant();
    }
}
