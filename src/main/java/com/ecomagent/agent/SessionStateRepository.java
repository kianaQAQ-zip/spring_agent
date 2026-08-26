package com.ecomagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * L2 会话状态持久化（§8.8.3 乐观锁并发写回）。
 *
 * <p>写回用 {@code UPDATE ... WHERE version=?} 乐观锁，受影响行数=0 即版本冲突，
 * 由上层重试（读-改-写循环）保证同 session 串行。
 */
@Repository
public class SessionStateRepository {

    private static final String SELECT_BY_SESSION =
            "SELECT session_id, tenant_id, state_json, version, updated_at "
                    + "FROM session_state WHERE session_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SessionState findBySessionId(String sessionId) {
        List<SessionState> list = jdbcTemplate.query(SELECT_BY_SESSION, (rs, i) -> {
            Map<String, Object> state = readState(rs.getString("state_json"));
            return new SessionState(
                    rs.getString("session_id"),
                    rs.getString("tenant_id"),
                    str(state.get("intent")),
                    str(state.get("orderId")),
                    str(state.get("emotion")),
                    rs.getLong("version"),
                    toInstant(rs.getTimestamp("updated_at")));
        }, sessionId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 首次写入（INSERT）。 */
    public void insert(SessionState state) {
        jdbcTemplate.update(
                "INSERT INTO session_state (id, session_id, tenant_id, state_json, version, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                java.util.UUID.randomUUID().toString(), state.sessionId(), state.tenantId(),
                writeState(state), state.version(), Timestamp.from(state.updatedAt()));
    }

    /**
     * 乐观锁写回：仅当 version 匹配才更新。返回 false 表示并发冲突（上层重试）。
     */
    public boolean update(SessionState state) {
        int rows = jdbcTemplate.update(
                "UPDATE session_state SET state_json = ?, version = version + 1, updated_at = ? "
                        + "WHERE session_id = ? AND version = ?",
                writeState(state), Timestamp.from(Instant.now()), state.sessionId(), state.version());
        return rows > 0;
    }

    private String writeState(SessionState s) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "intent", s.intent() == null ? "" : s.intent(),
                    "orderId", s.orderId() == null ? "" : s.orderId(),
                    "emotion", s.emotion() == null ? "" : s.emotion()));
        } catch (Exception e) {
            throw new IllegalStateException("serialize session state failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readState(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? Instant.now() : ts.toInstant();
    }
}
