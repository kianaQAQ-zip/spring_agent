package com.ecomagent.agent;

import com.ecomagent.rag.RetrievalPipeline;
import com.ecomagent.rag.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * M3 对话引擎 + M5 检索编排单测（@SpringBootTest）。
 * - qwenChatModel / RetrievalPipeline mock：避免真实模型调用与检索；
 * - 验证 citations 事件先发、流式 token 输出、成功流记忆落库、失败流补 [truncated] 记忆（§1.2）。
 */
@SpringBootTest
class ChatServiceTest {

    @MockBean
    @Qualifier("qwenChatModel")
    private ChatModel chatModel;

    @MockBean
    private RetrievalPipeline retrievalPipeline;

    @MockBean
    private VectorStore vectorStore;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatMemory chatMemory;

    private void stubRetrievalEmpty() {
        when(retrievalPipeline.retrieve(anyString(), anyString()))
                .thenReturn(new RetrievalResult(List.of(), List.of()));
    }

    @Test
    void streamAnswerEmitsCitationsThenTokensAndPersistsMemory() {
        stubRetrievalEmpty();
        ChatResponse resp = new ChatResponse(List.of(
                new Generation(new AssistantMessage("你好世界，七天无理由退货政策如下。"))));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(resp));

        String conv = "conv-1";
        List<ServerSentEvent<String>> events = chatService.streamAnswer(conv, "七天无理由退货怎么算")
                .collectList().block();

        assertNotNull(events);
        // 第一个事件应为 citations
        assertTrue(events.size() >= 2, "期望先发 citations 事件再发 token 事件");
        assertEquals("citations", events.get(0).event(), "首事件应为 citations");
        assertEquals("token", events.get(1).event(), "次事件应为 token");

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
        stubRetrievalEmpty();
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("boom")));

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
