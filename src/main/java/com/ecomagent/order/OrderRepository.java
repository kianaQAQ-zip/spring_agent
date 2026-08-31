package com.ecomagent.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 订单数据访问（真实数据源，替换原 mock）。
 *
 * <p>业务前提：单商家多平台。所有查询强制带 {@code tenant_id} 过滤，
 * 并可选按 {@code platform} 收窄；订单号在同租户内唯一。
 *
 * <p>只读——写操作（退款/改地址）属高风险动作，走 §2 确认护栏，不在此处。
 */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 订单主记录 + 最新一条物流轨迹（LEFT JOIN 取 seq 最大者）。 */
    public OrderRecord findByOrderId(String tenantId, String orderId) {
        List<OrderRecord> list = jdbcTemplate.query("""
                        SELECT o.order_id, o.platform, o.buyer_name, o.buyer_phone, o.status,
                               o.amount, o.item_title, o.quantity, o.address,
                               o.carrier, o.tracking_no, o.created_at,
                               t.node AS latest_trace
                        FROM orders o
                        LEFT JOIN order_trace t
                               ON t.order_id = o.order_id AND t.tenant_id = o.tenant_id
                              AND t.seq = (SELECT max(seq) FROM order_trace
                                            WHERE order_id = o.order_id AND tenant_id = o.tenant_id)
                        WHERE o.tenant_id = ? AND upper(o.order_id) = upper(?)
                        LIMIT 1
                        """,
                (rs, i) -> new OrderRecord(
                        rs.getString("order_id"),
                        rs.getString("platform"),
                        rs.getString("buyer_name"),
                        rs.getString("buyer_phone"),
                        rs.getString("status"),
                        rs.getBigDecimal("amount"),
                        rs.getString("item_title"),
                        rs.getInt("quantity"),
                        rs.getString("address"),
                        rs.getString("carrier"),
                        rs.getString("tracking_no"),
                        rs.getString("latest_trace"),
                        toInstant(rs.getTimestamp("created_at"))),
                tenantId, orderId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 物流轨迹倒序（最新在前）。 */
    public List<TraceNode> findTrace(String tenantId, String orderId, int limit) {
        return jdbcTemplate.query("""
                        SELECT node, happened_at, seq
                        FROM order_trace
                        WHERE tenant_id = ? AND upper(order_id) = upper(?)
                        ORDER BY seq DESC
                        LIMIT ?
                        """,
                (rs, i) -> new TraceNode(
                        rs.getInt("seq"),
                        rs.getString("node"),
                        toInstant(rs.getTimestamp("happened_at"))),
                tenantId, orderId, limit);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? Instant.EPOCH : ts.toInstant();
    }
}
