package com.ecomagent.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * L2 会话状态提取集成测试（§8 Layer 2）：增量提取 + 乐观锁写回（H2）。
 */
@SpringBootTest
class SessionStateServiceTest {

    @MockBean
    @Qualifier("qwenTurboChatModel")
    private ChatModel qwenTurbo;

    @Autowired
    private SessionStateService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS session_state ("
                + "id VARCHAR(64) PRIMARY KEY, session_id VARCHAR(64), tenant_id VARCHAR(64), "
                + "state_json TEXT, version BIGINT, updated_at TIMESTAMP, "
                + "UNIQUE(session_id, tenant_id))");
        jdbcTemplate.execute("DELETE FROM session_state");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM session_state");
    }

    @Test
    void extractsAndPersistsIncrementalState() {
        when(qwenTurbo.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage(
                        "{\"intent\":\"REFUND\",\"orderId\":\"ORD-1001\",\"emotion\":\"\",\"noChange\":false}")))));

        SessionState state = service.extractState("conv-1", "我要退 ORD-1001");

        assertNotNull(state);
        assertEquals("REFUND", state.intent());
        assertEquals("ORD-1001", state.orderId());
    }

    @Test
    void llmFailureDoesNotThrow() {
        when(qwenTurbo.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));

        SessionState state = service.extractState("conv-2", "你好");

        assertNotNull(state, "LLM 失败应保守降级，不阻塞对话");
    }
}
