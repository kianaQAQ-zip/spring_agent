package com.ecomagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * M3 对话引擎单测（@SpringBootTest）。
 * - qwenChatModel / VectorStore mock：避免真实模型调用与 H2-pgvector 依赖；
 * - 验证流式 token 输出、成功流记忆落库、失败流补 [truncated] 记忆（§1.2 防打断丢轮）。
 */
@SpringBootTest
class ChatServiceTest {

    @MockBean
    @Qualifier("qwenChatModel")
    private ChatModel chatModel;

    @MockBean
    private VectorStore vectorStore;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMemory chatMemory;

    @Test
    void streamAnswerReturnsTokensAndPersistsMemory() {
        ChatResponse resp = new ChatResponse(List.of(
                new Generation(new AssistantMessage("你好世界，七天无理由退货政策如下。"))));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(resp));
        when(vectorStore.similaritySearch(any())).thenReturn(List.of());

        String conv = "conv-1";
        List<ServerSentEvent<String>> events = chatService.streamAnswer(conv, "七天无理由退货怎么算")
                .collectList().block();

        assertNotNull(events);
        String joined = events.stream()
                .map(ServerSentEvent::data)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
        assertTrue(joined.contains("你好世界"), "期望流式内容包含模型回答: " + joined);

        // 记忆落库：1 user + 1 assistant
        List<Message> mem = chatMemory.get(conv);
        assertEquals(2, mem.size(), "期望记忆含 1 user + 1 assistant，实际: " + mem);
        assertTrue(mem.get(1) instanceof AssistantMessage);
    }

    @Test
    void streamErrorPersistsTruncatedMemory() {
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("boom")));
        when(vectorStore.similaritySearch(any())).thenReturn(List.of());

        String conv = "conv-err";
        try {
            chatService.streamAnswer(conv, "你好").collectList().block();
        } catch (Exception ignored) {
            // 错误流：collectList 会抛，忽略；重点验证 onError 补记忆
        }

        // onError 补 [truncated] 标记；user 消息已由 Memory Advisor 写入
        List<Message> mem = chatMemory.get(conv);
        boolean hasTruncated = mem.stream().anyMatch(m ->
                m instanceof AssistantMessage
                        && ((AssistantMessage) m).getText().contains("[truncated"));
        assertTrue(hasTruncated, "期望错误流补 truncated 记忆，实际: " + mem);
    }
}
