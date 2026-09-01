package com.ecomagent.handoff;

import com.ecomagent.common.PlatformContext;
import com.ecomagent.common.TenantContext;
import com.ecomagent.conversation.MessageRecord;
import com.ecomagent.conversation.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 转人工工单服务。
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>幂等</b>：同一会话 + 同一原因，5 分钟内已有未关闭工单则不重复建——
 *       否则一轮对话可能刷出一叠工单；</li>
 *   <li><b>上下文固化</b>：工单可能几小时后才被处理，那时会话记录可能已被清理，
 *       所以建单时就把对话历史快照写进 {@code context} 列；</li>
 *   <li><b>旁路</b>：建单失败只记日志，绝不影响对话本身。</li>
 * </ul>
 */
@Service
public class HandoffService {

    private static final Logger log = LoggerFactory.getLogger(HandoffService.class);
    private static final int CONTEXT_MESSAGES = 10;   // 快照保留最近 10 条
    private static final long DEDUP_WINDOW_SECONDS = 300;

    private final JdbcTemplate jdbcTemplate;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public HandoffService(JdbcTemplate jdbcTemplate,
                          MessageRepository messageRepository,
                          ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    /** 建工单（幂等）。已有同会话同原因的未关闭工单则跳过。 */
    public boolean createIfNeeded(String conversationId, HandoffTicket.Reason reason, String detail) {
        try {
            String tenantId = TenantContext.get();
            Integer dup = jdbcTemplate.queryForObject("""
                            SELECT count(*) FROM handoff_ticket
                            WHERE tenant_id = ? AND conversation_id = ? AND reason = ?
                              AND status <> ? AND created_at > now() - (? * interval '1 second')
                            """, Integer.class, tenantId, conversationId, reason.name(),
                    HandoffTicket.STATUS_CLOSED, DEDUP_WINDOW_SECONDS);
            if (dup != null && dup > 0) {
                return false;
            }
            jdbcTemplate.update("""
                            INSERT INTO handoff_ticket
                                (id, conversation_id, tenant_id, platform, reason, detail, context, status)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    java.util.UUID.randomUUID().toString(), conversationId, tenantId,
                    PlatformContext.code(), reason.name(), detail,
                    snapshotContext(tenantId, conversationId), HandoffTicket.STATUS_OPEN);
            log.info("转人工工单已创建: conversation={} reason={}", conversationId, reason);
            return true;
        } catch (Exception e) {
            log.warn("转人工工单创建失败: {}", e.getMessage());
            return false;
        }
    }

    public List<Map<String, Object>> list(String status) {
        String tenantId = TenantContext.get();
        if (status == null || status.isBlank()) {
            return jdbcTemplate.queryForList("""
                    SELECT id, conversation_id, platform, reason, detail, status,
                           operator, created_at, claimed_at, closed_at
                    FROM handoff_ticket WHERE tenant_id = ?
                    ORDER BY created_at DESC LIMIT 200
                    """, tenantId);
        }
        return jdbcTemplate.queryForList("""
                SELECT id, conversation_id, platform, reason, detail, status,
                       operator, created_at, claimed_at, closed_at
                FROM handoff_ticket WHERE tenant_id = ? AND status = ?
                ORDER BY created_at DESC LIMIT 200
                """, tenantId, status);
    }

    /** 受理：原子翻转，已处理则无效果。 */
    public boolean claim(String id, String operator) {
        int rows = jdbcTemplate.update(
                "UPDATE handoff_ticket SET status = ?, operator = ?, claimed_at = ? "
                        + "WHERE id = ? AND status = ?",
                HandoffTicket.STATUS_CLAIMED, operator, Timestamp.from(Instant.now()),
                id, HandoffTicket.STATUS_OPEN);
        return rows > 0;
    }

    public boolean close(String id, String operator) {
        int rows = jdbcTemplate.update(
                "UPDATE handoff_ticket SET status = ?, operator = ?, closed_at = ? "
                        + "WHERE id = ? AND status <> ?",
                HandoffTicket.STATUS_CLOSED, operator, Timestamp.from(Instant.now()),
                id, HandoffTicket.STATUS_CLOSED);
        return rows > 0;
    }

    /** 对话历史快照（最近 N 条），随工单固化。 */
    private String snapshotContext(String tenantId, String conversationId) {
        try {
            List<MessageRecord> msgs = messageRepository.findByConversation(tenantId, conversationId);
            int from = Math.max(0, msgs.size() - CONTEXT_MESSAGES);
            List<Map<String, Object>> tail = msgs.subList(from, msgs.size()).stream()
                    .map(m -> {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("role", m.role());
                        e.put("content", m.content());
                        return e;
                    })
                    .toList();
            return objectMapper.writeValueAsString(tail);
        } catch (Exception e) {
            return "[]";
        }
    }
}
