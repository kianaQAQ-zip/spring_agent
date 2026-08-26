package com.ecomagent.agent;

import com.ecomagent.common.TenantContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 对话编排服务（M3 对话引擎）。
 *
 * <p>Advisor 链（Memory → RAG → Observation）：
 * <ul>
 *   <li>{@link MessageChatMemoryAdvisor}：L1 短期记忆，窗口最近 8 轮（见 {@code ChatClientConfig.chatMemory}）。</li>
 *   <li>{@link RetrievalAugmentationAdvisor}：基础 RAG 召回 + 上下文注入（topK=4，相似度阈值 0.78，
 *       tenant_id 过滤，保证单商户/多租户隔离）。1.0.0 GA 中 QuestionAnswerAdvisor 已被本类取代。</li>
 * </ul>
 *
 * <p>流式边界（§1.2）：成功流由 {@link MessageChatMemoryAdvisor} 自动落库 user+assistant；
 * 失败/客户端断连时 Advisor 不写 assistant 消息，故仅在 onError 手动补一条 [truncated] 标记，
 * 既防打断丢轮、又不与 Advisor 的自动落库重复。
 */
@Service
public class ChatService {

    private static final int RAG_TOP_K = 4;
    private static final double RAG_SIMILARITY_THRESHOLD = 0.78;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ChatService(@Qualifier("qwenChatModel") ChatModel chatModel,
                       VectorStore vectorStore,
                       ChatMemory chatMemory) {
        // 租户过滤：tenant 为受控常量，拼装 filterExpression 安全无注入风险（M9 接入 TenantContext 动态取值）
        Filter.Expression tenantFilter = new FilterExpressionBuilder()
                .eq("tenant_id", TenantContext.get())
                .build();

        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(RAG_TOP_K)
                .similarityThreshold(RAG_SIMILARITY_THRESHOLD)
                .filterExpression(tenantFilter)
                .build();

        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(true).build())
                .build();

        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        this.chatMemory = chatMemory;
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(memoryAdvisor, ragAdvisor)
                .build();
    }

    /**
     * 流式回答：逐 token 以 SSE {@code event: token} 推送。
     *
     * @param conversationId 会话 ID（记忆窗口键）
     * @param userMessage    用户本轮消息
     */
    public Flux<ServerSentEvent<String>> streamAnswer(String conversationId, String userMessage) {
        StringBuilder acc = new StringBuilder();
        Flux<String> content = chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();

        return content
                .doOnNext(acc::append)
                .map(token -> ServerSentEvent.<String>builder()
                        .event("token")
                        .data(token)
                        .build())
                .doOnError(e -> persistTruncated(conversationId, acc.toString()))
                // 成功流：MessageChatMemoryAdvisor 已在流完成时将 user+assistant 落库，无需手动补
                .doOnComplete(() -> { });
    }

    /** 失败/断连时补一条截断标记记忆，确保该轮不丢失（§1.2 防打断丢轮） */
    public void persistTruncated(String conversationId, String partial) {
        String content = (partial == null || partial.isBlank())
                ? "[truncated: stream interrupted]"
                : partial + " [truncated]";
        chatMemory.add(conversationId, new AssistantMessage(content));
    }

    public ChatClient getChatClient() {
        return chatClient;
    }
}
