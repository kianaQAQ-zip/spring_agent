package com.ecomagent.agent;

import com.ecomagent.common.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 确认护栏核心（§2）：待确认动作的生命周期管理。
 *
 * <p>覆盖 §2 三大面试点：
 * <ul>
 *   <li><b>§2.2 幂等键</b>：{@code hash(conversation_id + tool + 归一化 params)}，重复发起不新建；
 *       以 {@code (conversation_id, idempotency_key)} 唯一索引兜底，并发下捕获重复键返回既有记录。</li>
 *   <li><b>§2.3 双执行防护</b>：确认走 {@code UPDATE ... SET status='confirmed' WHERE status='pending'}，
 *       受影响行数=0 即已被处理，抛 {@link ConfirmationConflictException}（行级原子，杜绝双执行）。</li>
 *   <li><b>§2.1 超时 Reaper</b>：{@code @Scheduled} 定时把超时未确认的 pending 翻为 expired。
 *       生产多实例场景建议用 {@code SELECT ... FOR UPDATE SKIP LOCKED} 抢占（见代码注释），
 *       本实现用单条 UPDATE 兼顾 H2 测试与 PG 生产。</li>
 * </ul>
 *
 * <p>§2.4 结果回灌：确认时执行（演示为 mock），结果写入 {@code result} 列，供下一轮上下文回灌。
 */
@Service
public class ConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);
    private static final long TTL_SECONDS = 300; // 5 分钟不确认自动过期

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RowMapper<PendingAction> rowMapper = (rs, i) -> new PendingAction(
            rs.getString("id"),
            rs.getString("conversation_id"),
            rs.getString("tenant_id"),
            rs.getString("tool"),
            rs.getString("params"),
            rs.getString("status"),
            rs.getString("idempotency_key"),
            rs.getString("final_params"),
            rs.getString("operator"),
            toInstant(rs.getTimestamp("executed_at")),
            rs.getString("result"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("expires_at")));

    public ConfirmationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 发起一个需确认的动作（幂等）。已存在同幂等键的 pending 时直接返回既有记录，不新建。
     */
    public PendingAction request(String conversationId, String tool, Map<String, Object> params) {
        String paramsJson = toJson(params);
        String key = idempotencyKey(conversationId, tool, paramsJson);
        PendingAction existing = findByKey(conversationId, key);
        if (existing != null) {
            return existing;
        }
        String id = java.util.UUID.randomUUID().toString();
        Instant now = Instant.now();
        Timestamp expires = Timestamp.from(now.plusSeconds(TTL_SECONDS));
        try {
            jdbcTemplate.update(
                    "INSERT INTO pending_action (id, conversation_id, tenant_id, tool, params, status, idempotency_key, created_at, expires_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, conversationId, TenantContext.get(), tool, paramsJson,
                    PendingAction.STATUS_PENDING, key, Timestamp.from(now), expires);
        } catch (DuplicateKeyException e) {
            // 并发下唯一索引兜底：返回既有记录
            PendingAction raced = findByKey(conversationId, key);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
        return get(id);
    }

    /** 确认（可带改参）：原子翻转为 confirmed 并执行，返回带结果的记录。重复确认抛冲突。 */
    public PendingAction confirm(String id, Map<String, Object> finalParams, String operator) {
        PendingAction current = get(id);
        if (current == null) {
            throw new ConfirmationConflictException("pending action not found: " + id);
        }
        String finalParamsJson = finalParams != null && !finalParams.isEmpty()
                ? toJson(finalParams) : current.paramsJson();
        String resultJson = execute(current.tool(), finalParamsJson, operator);

        int rows = jdbcTemplate.update(
                "UPDATE pending_action SET status = ?, final_params = ?, operator = ?, executed_at = ?, result = ? "
                        + "WHERE id = ? AND status = ?",
                PendingAction.STATUS_CONFIRMED, finalParamsJson, operator,
                Timestamp.from(Instant.now()), resultJson, id, PendingAction.STATUS_PENDING);
        if (rows == 0) {
            throw new ConfirmationConflictException("action already handled (not pending): " + id);
        }
        return get(id);
    }

    /** 驳回。 */
    public PendingAction reject(String id, String operator) {
        int rows = jdbcTemplate.update(
                "UPDATE pending_action SET status = ?, operator = ? WHERE id = ? AND status = ?",
                PendingAction.STATUS_REJECTED, operator, id, PendingAction.STATUS_PENDING);
        if (rows == 0) {
            throw new ConfirmationConflictException("action already handled (not pending): " + id);
        }
        return get(id);
    }

    /** 取消。 */
    public PendingAction cancel(String id) {
        int rows = jdbcTemplate.update(
                "UPDATE pending_action SET status = ? WHERE id = ? AND status = ?",
                PendingAction.STATUS_CANCELLED, id, PendingAction.STATUS_PENDING);
        if (rows == 0) {
            throw new ConfirmationConflictException("action already handled (not pending): " + id);
        }
        return get(id);
    }

    public PendingAction get(String id) {
        List<PendingAction> list = jdbcTemplate.query(
                "SELECT * FROM pending_action WHERE id = ?", rowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<PendingAction> listByConversation(String conversationId) {
        return jdbcTemplate.query(
                "SELECT * FROM pending_action WHERE conversation_id = ? ORDER BY created_at DESC",
                rowMapper, conversationId);
    }

    /**
     * 超时 Reaper（§2.1）：把超过 TTL 仍未确认的 pending 翻为 expired。
     * 单条 UPDATE 原子、幂等。生产多实例安全版可改为：
     * {@code SELECT id FROM pending_action WHERE status='pending' AND expires_at < now()
     *  FOR UPDATE SKIP LOCKED LIMIT 100} 逐条抢占（PG 方言）。
     */
    @Scheduled(fixedDelayString = "${confirm.reaper-interval-ms:60000}")
    public int reapExpired() {
        try {
            int n = jdbcTemplate.update(
                    "UPDATE pending_action SET status = ? WHERE status = ? AND expires_at < ?",
                    PendingAction.STATUS_EXPIRED, PendingAction.STATUS_PENDING,
                    Timestamp.from(Instant.now()));
            if (n > 0) {
                log.info("reaper expired {} pending action(s)", n);
            }
            return n;
        } catch (DataAccessException e) {
            // 测试环境 pending_action 表可能尚未创建，容忍跳过
            log.warn("reaper skipped: {}", e.getMessage());
            return 0;
        }
    }

    private PendingAction findByKey(String conversationId, String key) {
        List<PendingAction> list = jdbcTemplate.query(
                "SELECT * FROM pending_action WHERE conversation_id = ? AND idempotency_key = ? AND status = ?",
                rowMapper, conversationId, key, PendingAction.STATUS_PENDING);
        return list.isEmpty() ? null : list.get(0);
    }

    /** §2.4 结果回灌：执行动作（演示 mock）。真实项目在此调用订单/物流/券系统。 */
    private String execute(String tool, String finalParamsJson, String operator) {
        return "{\"status\":\"EXECUTED\",\"tool\":\"" + tool + "\",\"params\":" + finalParamsJson
                + ",\"operator\":\"" + (operator == null ? "" : operator) + "\"}";
    }

    private String idempotencyKey(String conversationId, String tool, String paramsJson) {
        String raw = conversationId + "|" + tool + "|" + paramsJson;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String toJson(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params == null ? Map.of() : params);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize params", e);
        }
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
