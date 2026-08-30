package com.ecomagent.common;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 降级链单测：主模型失败切备用 / 全挂时抛错并标记 / 流式已吐 token 后不切换。
 */
class FallbackChatModelTest {

    private static ChatResponse resp(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void usesPrimaryWhenHealthy() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fb = mock(ChatModel.class);
        DegradationFlags flags = new DegradationFlags();
        when(primary.call(any(Prompt.class))).thenReturn(resp("primary"));

        ChatModel m = new FallbackChatModel(primary, List.of(fb), flags, DegradationFlags.CHAT);
        assertEquals("primary", m.call(new Prompt("hi")).getResult().getOutput().getText());
        assertEquals(List.of(), flags.degraded());
    }

    @Test
    void fallsBackWhenPrimaryThrows() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fb = mock(ChatModel.class);
        DegradationFlags flags = new DegradationFlags();
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("403 quota"));
        when(fb.call(any(Prompt.class))).thenReturn(resp("from fallback"));

        ChatModel m = new FallbackChatModel(primary, List.of(fb), flags, DegradationFlags.CHAT);
        assertEquals("from fallback", m.call(new Prompt("hi")).getResult().getOutput().getText());
        assertEquals(List.of(), flags.degraded(), "降级成功后不应标记为 degraded");
    }

    @Test
    void marksDegradedWhenAllFail() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fb = mock(ChatModel.class);
        DegradationFlags flags = new DegradationFlags();
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("403"));
        when(fb.call(any(Prompt.class))).thenThrow(new RuntimeException("403"));

        ChatModel m = new FallbackChatModel(primary, List.of(fb), flags, DegradationFlags.CHAT);
        assertThrows(RuntimeException.class, () -> m.call(new Prompt("hi")));
        assertEquals(List.of(DegradationFlags.CHAT), flags.degraded());
    }

    @Test
    void noFallbackConfiguredPropagatesImmediately() {
        ChatModel primary = mock(ChatModel.class);
        DegradationFlags flags = new DegradationFlags();
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("403"));

        ChatModel m = new FallbackChatModel(primary, List.of(), flags, DegradationFlags.CHAT);
        assertThrows(RuntimeException.class, () -> m.call(new Prompt("hi")));
        assertEquals(List.of(DegradationFlags.CHAT), flags.degraded());
    }

    @Test
    void streamSwitchesBeforeFirstToken() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fb = mock(ChatModel.class);
        DegradationFlags flags = new DegradationFlags();
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("403")));
        when(fb.stream(any(Prompt.class))).thenReturn(Flux.just(resp("a"), resp("b")));

        ChatModel m = new FallbackChatModel(primary, List.of(fb), flags, DegradationFlags.CHAT);
        List<ChatResponse> out = m.stream(new Prompt("hi")).collectList().block();

        assertEquals(2, out.size(), "第一个 token 之前失败，应整体切到备用模型");
        assertEquals(List.of(), flags.degraded());
    }

    @Test
    void streamDoesNotSwitchAfterTokensEmitted() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fb = mock(ChatModel.class);
        DegradationFlags flags = new DegradationFlags();
        // 吐一个 token 后才失败——切换会导致输出重复
        when(primary.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(Flux.just(resp("partial")), Flux.error(new RuntimeException("boom"))));
        when(fb.stream(any(Prompt.class))).thenReturn(Flux.just(resp("from fallback")));

        ChatModel m = new FallbackChatModel(primary, List.of(fb), flags, DegradationFlags.CHAT);

        List<ChatResponse> received = new ArrayList<>();
        RuntimeException thrown = null;
        try {
            m.stream(new Prompt("hi")).doOnNext(received::add).blockLast();
        } catch (RuntimeException e) {
            thrown = e;
        }

        assertNotNull(thrown, "已吐 token 后失败应原样抛错，不能切模型");
        assertEquals(1, received.size(), "不应追加备用模型的输出，否则前端会出现重复内容");
        assertEquals("partial", received.get(0).getResult().getOutput().getText());
        assertEquals(List.of(DegradationFlags.CHAT), flags.degraded());
    }
}
