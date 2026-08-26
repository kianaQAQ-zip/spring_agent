package com.ecomagent.agent;

import com.ecomagent.common.TenantContext;
import com.ecomagent.rag.CitationValidator;
import com.ecomagent.rag.RetrievalPipeline;
import com.ecomagent.rag.RetrievalResult;
import com.ecomagent.tools.AddressChangeTool;
import com.ecomagent.tools.CouponTool;
import com.ecomagent.tools.OrderQueryTool;
import com.ecomagent.tools.RefundTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 对话编排服务（M3 对话引擎 + M4 工具 + M5 检索溯源）。
 *
 * <p>Advisor 链保留 {@link MessageChatMemoryAdvisor}（L1 短期记忆）；RAG 检索改手动编排
 * （{@link RetrievalPipeline}：混合召回 → 重排 → MMR → 编号），以便拿到最终排名列表，
 * 先发 {@code event: citations} 再发 {@code event: token}，并对回答做 {@code [n]} 越界后校验。
 *
 * <p>流式边界（§1.2）：成功流由 {@link MessageChatMemoryAdvisor} 自动落库 user+assistant；
 * 失败/断连时 Advisor 不写 assistant，故仅在 onError 手动补 [truncated] 标记防丢轮。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final RetrievalPipeline retrievalPipeline;
    private final ObjectMapper objectMapper;

    public ChatService(@Qualifier("qwenChatModel") ChatModel chatModel,
                       ChatMemory chatMemory,
                       RetrievalPipeline retrievalPipeline,
                       OrderQueryTool orderQueryTool,
                       RefundTool refundTool,
                       AddressChangeTool addressChangeTool,
                       CouponTool couponTool,
                       ObjectMapper objectMapper) {
        this.chatMemory = chatMemory;
        this.retrievalPipeline = retrievalPipeline;
        this.objectMapper = objectMapper;

        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(memoryAdvisor)
                // M4：工具层（只读直执行；@ConfirmRequired 工具经 ConfirmationService 落 pending）
                .defaultTools(orderQueryTool, refundTool, addressChangeTool, couponTool)
                .build();
    }

    /**
     * 流式回答：先发 {@code event: citations}（引用列表），再逐 token 以 {@code event: token} 推送。
     */
    public Flux<ServerSentEvent<String>> streamAnswer(String conversationId, String userMessage) {
        // 1. 检索（同步）：混合召回 → 重排 → MMR → 编号
        RetrievalResult rr = retrievalPipeline.retrieve(userMessage, TenantContext.get());
        String systemPrompt = buildRagSystemPrompt(rr);

        ServerSentEvent<String> citationsEvent = ServerSentEvent.<String>builder()
                .event("citations")
                .data(toJson(rr.citations()))
                .build();

        StringBuilder acc = new StringBuilder();
        Flux<ServerSentEvent<String>> textEvents = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(Map.of("conversationId", conversationId))
                .stream()
                .content()
                .doOnNext(acc::append)
                .map(token -> ServerSentEvent.<String>builder().event("token").data(token).build());

        return Flux.concat(Flux.just(citationsEvent), textEvents)
                .doOnError(e -> persistTruncated(conversationId, acc.toString()))
                // 成功流：MessageChatMemoryAdvisor 已在流完成时落库，无需手动补
                .doOnComplete(() -> checkCitations(acc.toString(), rr.citations().size()));
    }

    private String buildRagSystemPrompt(RetrievalResult rr) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是电商客服助手。请严格依据以下知识库片段回答，并在引用处标注来源编号 [n]（n 对应片段编号）。\n\n");
        for (int i = 0; i < rr.documents().size(); i++) {
            sb.append("[").append(i + 1).append("] ")
                    .append(rr.documents().get(i).getText()).append("\n\n");
        }
        sb.append("若知识库不足以回答，请如实说明，不要编造。");
        return sb.toString();
    }

    /** §5 后处理：校验 [n] 引用不越界（防模型编造引用），越界仅告警不阻断。 */
    private void checkCitations(String text, int maxIndex) {
        List<Integer> out = CitationValidator.outOfRange(text, maxIndex);
        if (!out.isEmpty()) {
            log.warn("回答含越界引用 [n]（n>{}）: {}", maxIndex, out);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
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
