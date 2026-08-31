package com.ecomagent.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 消息持久化（真实数据源）。
 *
 * <p>写入即落库，不做批量缓冲——客服场景单会话消息频率低，直写足够；
 * 未来量大再上 {@code JdbcTemplate.batchUpdate}。
 * 时间列交给 PG {@code now()} 填，规避 JDBC 对 {@code Instant} 的类型推断陷阱。
 */
@Repository
public class MessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String conversationId, String tenantId, String platform,
                       String role, String content, boolean piiMasked) {
        jdbcTemplate.update("""
                        INSERT INTO message
                            (id, conversation_id, tenant_id, platform, role, content, pii_masked)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                java.util.UUID.randomUUID().toString(),
                conversationId, tenantId, platform, role, content, piiMasked);
    }

    /** 按时间正序拉取某会话全部消息（M2 历史回溯 / 导出用）。 */
    public List<MessageRecord> findByConversation(String tenantId, String conversationId) {
        return jdbcTemplate.query("""
                        SELECT conversation_id, tenant_id, platform, role, content, pii_masked, created_at
                        FROM message
                        WHERE tenant_id = ? AND conversation_id = ?
                        ORDER BY created_at ASC
                        """,
                (rs, i) -> new MessageRecord(
                        rs.getString("conversation_id"),
                        rs.getString("tenant_id"),
                        rs.getString("platform"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getBoolean("pii_masked"),
                        toInstant(rs.getTimestamp("created_at"))),
                tenantId, conversationId);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? Instant.EPOCH : ts.toInstant();
    }
}
