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
 * 对话编排服务（M3 对话引擎 + M4 工具 + M5 检索溯源 + M6 状态机/QueryRewrite）。
 *
 * <p>流程：信号检测 → L2 状态提取 → 状态机（主动澄清）→ QueryRewrite → 混合检索 → 上下文装配 → 流式。
 * Advisor 链保留 {@link MessageChatMemoryAdvisor}（L1 短期记忆）；RAG 检索手动编排（{@link RetrievalPipeline}）。
 *
 * <p>流式边界（§1.2）：成功流由 Memory Advisor 自动落库；失败/断连时 onError 补 [truncated] 防丢轮。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final RetrievalPipeline retrievalPipeline;
    private final SignalDetector signalDetector;
    private final StateMachine stateMachine;
    private final SessionStateService sessionStateService;
    private final QueryRewriteService queryRewriteService;
    private final ContextAssembler contextAssembler;
    private final ObjectMapper objectMapper;

    public ChatService(@Qualifier("qwenChatModel") ChatModel chatModel,
                       ChatMemory chatMemory,
                       RetrievalPipeline retrievalPipeline,
                       SignalDetector signalDetector,
                       StateMachine stateMachine,
                       SessionStateService sessionStateService,
                       QueryRewriteService queryRewriteService,
                       ContextAssembler contextAssembler,
                       OrderQueryTool orderQueryTool,
                       RefundTool refundTool,
                       AddressChangeTool addressChangeTool,
                       CouponTool couponTool,
                       ObjectMapper objectMapper) {
        this.chatMemory = chatMemory;
        this.retrievalPipeline = retrievalPipeline;
        this.signalDetector = signalDetector;
        this.stateMachine = stateMachine;
        this.sessionStateService = sessionStateService;
        this.queryRewriteService = queryRewriteService;
        this.contextAssembler = contextAssembler;
        this.objectMapper = objectMapper;

        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(memoryAdvisor)
                // M4：工具层（只读直执行；@ConfirmRequired 工具经 ConfirmationService 落 pending）
                .defaultTools(orderQueryTool, refundTool, addressChangeTool, couponTool)
                .build();
    }

    /**
     * 流式回答：先发 {@code event: citations}，再逐 token 以 {@code event: token} 推送。
     */
    public Flux<ServerSentEvent<String>> streamAnswer(String conversationId, String userMessage) {
        // 1. 信号检测（纯规则，同步）
        Signal signal = signalDetector.detect(userMessage);

        // 2. L2 状态提取（qwen-turbo 增量，best-effort）
        SessionState state = sessionStateService.extractState(conversationId, userMessage);

        // 3. 状态机：主动澄清
        Decision decision = stateMachine.decide(state.intent(), state.orderId(), signal);
        if (decision.needsClarification()) {
            return clarificationFlux(decision.reason());
        }

        // 4. QueryRewrite（L1 感知改写，仅改检索句）
        String rewritten = queryRewriteService.rewrite(conversationId, userMessage);

        // 5. 混合检索 → 重排 → MMR → 编号
        RetrievalResult rr = retrievalPipeline.retrieve(rewritten, TenantContext.get());

        // 6. 上下文装配（token 预算 + 状态分区）
        String systemPrompt = contextAssembler.assemble(state, rr.documents());

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
                .doOnComplete(() -> checkCitations(acc.toString(), rr.citations().size()));
    }

    private Flux<ServerSentEvent<String>> clarificationFlux(String reason) {
        return Flux.just(ServerSentEvent.<String>builder().event("token").data(reason).build());
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
