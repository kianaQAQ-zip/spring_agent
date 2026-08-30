package com.ecomagent.agent;

import com.ecomagent.common.DegradationFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QueryRewrite 单测（§3.1）：改写 + Caffeine 缓存命中。
 */
@ExtendWith(MockitoExtension.class)
class QueryRewriteServiceTest {

    @Mock
    private ChatModel qwenTurbo;
    @Mock
    private ChatMemory chatMemory;

    @Test
    void rewritesAndCaches() {
        QueryRewriteService service = new QueryRewriteService(qwenTurbo, chatMemory, new DegradationFlags());
        when(chatMemory.get(anyString())).thenReturn(List.of());
        when(qwenTurbo.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("ORD-1001 退款政策")))));

        String r1 = service.rewrite("conv-1", "这个能退吗");
        String r2 = service.rewrite("conv-1", "这个能退吗");

        assertEquals("ORD-1001 退款政策", r1);
        assertEquals(r1, r2, "相同 query+上下文应命中缓存");
        verify(qwenTurbo, times(1)).call(any(Prompt.class));
    }
}
