package com.ecomagent.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 会话持久化（真实数据源）。
 *
 * <p>幂等 upsert：同租户下 {@code conversation_id} 唯一（见 init.sql 的
 * {@code idx_conv_unique}），冲突时只刷新 {@code updated_at}，标题保留首次值。
 * 时间列交给 PG 的 {@code now()} 填——不传 {@code Instant} 给 JDBC，规避类型推断陷阱。
 */
@Repository
public class ConversationRepository {

    private static final String UPSERT = """
            INSERT INTO conversation (id, conversation_id, tenant_id, platform, title)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, conversation_id)
            DO UPDATE SET updated_at = now(),
                          title = COALESCE(conversation.title, EXCLUDED.title)
            """;

    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 会话首次出现时建记录，后续调用仅刷新活跃时间。
     *
     * @param title 首次会话的标题（取首条用户消息截断），null 表示沿用已有
     */
    public void upsert(String conversationId, String tenantId, String platform, String title) {
        jdbcTemplate.update(UPSERT,
                java.util.UUID.randomUUID().toString(),
                conversationId, tenantId, platform, title);
    }

    /** 纯刷新活跃时间（消息写入时附带调用）。 */
    public void touch(String conversationId, String tenantId) {
        jdbcTemplate.update(
                "UPDATE conversation SET updated_at = now() WHERE tenant_id = ? AND conversation_id = ?",
                tenantId, conversationId);
    }
}
