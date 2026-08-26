package com.ecomagent.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConfirmationService 确认护栏集成测试（§2，H2 内存库）。
 *
 * <p>覆盖三大面试点：幂等键（§2.2）、双执行防护（§2.3）、超时 Reaper（§2.1）+ 结果回灌（§2.4）。
 */
@SpringBootTest
class ConfirmationServiceTest {

    @Autowired
    private ConfirmationService confirmationService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS pending_action ("
                + "id VARCHAR(64) PRIMARY KEY, conversation_id VARCHAR(64) NOT NULL, "
                + "tenant_id VARCHAR(64) NOT NULL DEFAULT 'default', tool VARCHAR(64) NOT NULL, "
                + "params TEXT, status VARCHAR(16) NOT NULL DEFAULT 'pending', "
                + "idempotency_key VARCHAR(64) NOT NULL, final_params TEXT, operator VARCHAR(64), "
                + "executed_at TIMESTAMP, result TEXT, created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL)");
        jdbcTemplate.execute("DELETE FROM pending_action");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM pending_action");
    }

    private Map<String, Object> refundParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", "ORD-1001");
        params.put("reason", "质量问题");
        params.put("amount", 199.0);
        return params;
    }

    @Test
    void requestIsIdempotent() {
        PendingAction a1 = confirmationService.request("conv-1", "refund", refundParams());
        PendingAction a2 = confirmationService.request("conv-1", "refund", refundParams());

        assertNotNull(a1);
        assertEquals(a1.id(), a2.id(), "相同幂等键应返回同一 pending 记录");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pending_action WHERE conversation_id='conv-1'", Integer.class));
    }

    @Test
    void confirmExecutesAndReplaysResult() {
        PendingAction pending = confirmationService.request("conv-1", "refund", refundParams());

        PendingAction confirmed = confirmationService.confirm(pending.id(), null, "agent01");

        assertEquals(PendingAction.STATUS_CONFIRMED, confirmed.status());
        assertNotNull(confirmed.result());
        assertTrue(confirmed.result().contains("EXECUTED"), "确认后应回灌执行结果: " + confirmed.result());
        assertEquals("agent01", confirmed.operator());
    }

    @Test
    void doubleConfirmThrowsConflict() {
        PendingAction pending = confirmationService.request("conv-1", "refund", refundParams());
        confirmationService.confirm(pending.id(), null, "agent01");

        assertThrows(ConfirmationConflictException.class,
                () -> confirmationService.confirm(pending.id(), null, "agent02"),
                "重复确认应被行级 UPDATE 拦截，抛冲突");
    }

    @Test
    void rejectMarksRejected() {
        PendingAction pending = confirmationService.request("conv-1", "refund", refundParams());
        PendingAction rejected = confirmationService.reject(pending.id(), "agent01");
        assertEquals(PendingAction.STATUS_REJECTED, rejected.status());
    }

    @Test
    void cancelMarksCancelled() {
        PendingAction pending = confirmationService.request("conv-1", "refund", refundParams());
        PendingAction cancelled = confirmationService.cancel(pending.id());
        assertEquals(PendingAction.STATUS_CANCELLED, cancelled.status());
    }

    @Test
    void reaperExpiresOverduePending() {
        PendingAction pending = confirmationService.request("conv-1", "refund", refundParams());
        // 把过期时间改到过去，模拟超时未确认
        jdbcTemplate.update("UPDATE pending_action SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), pending.id());

        int expired = confirmationService.reapExpired();

        assertEquals(1, expired);
        assertEquals(PendingAction.STATUS_EXPIRED, confirmationService.get(pending.id()).status());
    }
}
