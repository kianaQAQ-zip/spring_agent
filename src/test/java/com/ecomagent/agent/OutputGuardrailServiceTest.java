package com.ecomagent.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 输出护栏单测（§1.1 + §5）：PII 脱敏 / 越界拒答 / 事实一致性。
 */
@ExtendWith(MockitoExtension.class)
class OutputGuardrailServiceTest {

    @Mock
    private ChatModel qwenTurbo;

    @Test
    void masksPiiInOutput() {
        OutputGuardrailService service = new OutputGuardrailService(qwenTurbo);
        String masked = service.maskOutput("您的手机号是13812348000");
        assertTrue(masked.contains("****"), "输出应脱敏");
        assertFalse(masked.contains("13812348000"));
    }

    @Test
    void strongAssertionWithoutCitationIsOutOfScope() {
        OutputGuardrailService service = new OutputGuardrailService(qwenTurbo);
        assertTrue(service.isOutOfScope("这个绝对可以退，我确定没问题"));
    }

    @Test
    void citedAnswerIsNotOutOfScope() {
        OutputGuardrailService service = new OutputGuardrailService(qwenTurbo);
        assertFalse(service.isOutOfScope("我肯定这符合退货政策[1]"));
    }

    @Test
    void judgeFailsOnHallucination() {
        OutputGuardrailService service = new OutputGuardrailService(qwenTurbo);
        when(qwenTurbo.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"verdict\":\"FAIL\",\"reason\":\"回答与知识库不符\"}")))));

        OutputGuardrailService.GuardrailResult r = service.judge("凭空编造的内容", "知识库片段");

        assertTrue(r.failed());
    }

    @Test
    void judgePassesOnGroundedAnswer() {
        OutputGuardrailService service = new OutputGuardrailService(qwenTurbo);
        when(qwenTurbo.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"verdict\":\"PASS\",\"reason\":\"\"}")))));

        OutputGuardrailService.GuardrailResult r = service.judge("依据知识库的回答", "知识库片段");

        assertFalse(r.failed());
    }
}
