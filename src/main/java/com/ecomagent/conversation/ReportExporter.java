package com.ecomagent.conversation;

import com.ecomagent.common.Platform;
import com.ecomagent.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 报表导出（L1 数据服务，Q4 运营向 CSV 明细）。
 *
 * <p>只产字节流，不查统计口径——明细给运营自己透视。加 UTF-8 BOM 让 Excel 双击即正确打开中文。
 */
@Component
public class ReportExporter {

    private final JdbcTemplate jdbcTemplate;

    public ReportExporter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 导出消息明细 CSV（按平台/关键词/时间筛选，时间正序）。 */
    public byte[] exportConversations(String platform, String keyword, String from, String to) {
        String tenantId = TenantContext.get();
        StringBuilder where = new StringBuilder(" WHERE m.tenant_id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);

        if (platform != null && !platform.isBlank() && !"unknown".equals(platform)) {
            where.append(" AND m.platform = ? ");
            args.add(platform);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND m.content ILIKE ? ");
            args.add("%" + keyword + "%");
        }
        if (from != null && !from.isBlank()) {
            where.append(" AND m.created_at >= ?::timestamptz ");
            args.add(from);
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND m.created_at <= ?::timestamptz ");
            args.add(to);
        }

        List<Object[]> rows = jdbcTemplate.query("""
                        SELECT m.conversation_id, m.platform, c.title, m.role, m.content, m.created_at
                        FROM message m
                        LEFT JOIN conversation c
                          ON c.conversation_id = m.conversation_id AND c.tenant_id = m.tenant_id
                        """ + where + """
                        ORDER BY m.created_at ASC
                        """,
                (rs, i) -> new Object[]{
                        rs.getString("conversation_id"),
                        rs.getString("platform"),
                        rs.getString("title"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at") == null ? "" : rs.getTimestamp("created_at").toString()
                }, args.toArray());

        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');  // UTF-8 BOM
        sb.append("会话ID,平台,标题,角色,内容,时间\n");
        for (Object[] r : rows) {
            sb.append(csv(r[0])).append(',')
              .append(csv(Platform.of(String.valueOf(r[1])).label())).append(',')
              .append(csv(r[2])).append(',')
              .append(csv(r[3])).append(',')
              .append(csv(r[4])).append(',')
              .append(csv(r[5])).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** CSV 字段转义：含逗号/引号/换行时加引号包裹，内部引号翻倍。 */
    private static String csv(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
