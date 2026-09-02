package com.ecomagent.agent;

import com.ecomagent.common.DegradationFlags;
import com.ecomagent.common.GlobalExceptionHandler;
import com.ecomagent.common.PlatformContext;
import com.ecomagent.common.TenantContext;
import com.ecomagent.conversation.ConversationPersistenceService;
import com.ecomagent.eval.CostCalculator;
import com.ecomagent.eval.RagEvalService;
import com.ecomagent.handoff.HandoffService;
import com.ecomagent.handoff.HandoffTicket;
import com.ecomagent.rag.Citation;
import com.ecomagent.rag.CitationValidator;
import com.ecomagent.rag.RetrievalPipeline;
import com.ecomagent.rag.RetrievalResult;
import com.ecomagent.rag.TokenUtils;
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
    private final OutputGuardrailService outputGuardrailService;
    private final DegradationFlags degradationFlags;
    private final ConversationPersistenceService conversationPersistence;
    private final RagEvalService ragEvalService;
    private final CostCalculator costCalculator;
    private final HandoffService handoffService;
    private final ObjectMapper objectMapper;

    public ChatService(@Qualifier("qwenChatModel") ChatModel chatModel,
                       ChatMemory chatMemory,
                       RetrievalPipeline retrievalPipeline,
                       SignalDetector signalDetector,
                       StateMachine stateMachine,
                       SessionStateService sessionStateService,
                       QueryRewriteService queryRewriteService,
                       ContextAssembler contextAssembler,
                       OutputGuardrailService outputGuardrailService,
                       DegradationFlags degradationFlags,
                       ConversationPersistenceService conversationPersistence,
                       RagEvalService ragEvalService,
                       CostCalculator costCalculator,
                       HandoffService handoffService,
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
        this.outputGuardrailService = outputGuardrailService;
        this.degradationFlags = degradationFlags;
        this.conversationPersistence = conversationPersistence;
        this.ragEvalService = ragEvalService;
        this.costCalculator = costCalculator;
        this.handoffService = handoffService;
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
        // 在此捕获上下文：SSE 的 doOnComplete/doOnError 跑在 Reactor 的 Netty 线程上，
        // ThreadLocal（TenantContext/PlatformContext）读不到，必须显式透传给落库调用，
        // 否则 assistant 消息 / rag_eval / 工单的 platform 会全落 unknown。
        final String tenantId = TenantContext.get();
        final String platform = PlatformContext.code();

        // 0. 用户消息进入即落库——即使后续流式失败，也保留下"用户问了什么"
        conversationPersistence.recordUserMessage(conversationId, userMessage, tenantId, platform);
        try {
            // 1. 信号检测（纯规则，同步）
            Signal signal = signalDetector.detect(userMessage);

            // 2. L2 状态提取（qwen-turbo 增量，best-effort）
            SessionState state = extractStateSafely(conversationId, userMessage);

            // 3. 状态机：主动澄清
            Decision decision = stateMachine.decide(state.intent(), state.orderId(), signal);
            if (decision.needsClarification()) {
                return clarificationFlux(decision.reason());
            }

            // 4. QueryRewrite（L1 感知改写，仅改检索句；best-effort）
            String rewritten;
            try {
                rewritten = queryRewriteService.rewrite(conversationId, userMessage);
            } catch (Exception e) {
                log.warn("QueryRewrite 失败（best-effort 跳过）: {}", e.getMessage());
                rewritten = userMessage;
            }

            // 5. 混合检索 → 重排 → MMR → 编号
            RetrievalResult rr = retrievalPipeline.retrieve(rewritten, TenantContext.get());

            // 6. 上下文装配（token 预算 + 状态分区）
            String systemPrompt = contextAssembler.assemble(state, rr.documents());

            // 降级信号随 citations 一起下发：同步阶段被吞掉的失败（状态提取/查询改写）
            // 必须让前端可见——否则用户以为在跟完整 Agent 聊，实际工具调用已经全废。
            ServerSentEvent<String> citationsEvent = ServerSentEvent.<String>builder()
                    .event("citations")
                    .data(toJson(new CitationsPayload(rr.citations(), degradationFlags.degraded())))
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
                    // M7：PII 输出脱敏（§5 输出缝），逐 token 掩码后推前端
                    .map(token -> ServerSentEvent.<String>builder()
                            .event("token")
                            .data(outputGuardrailService.maskOutput(token))
                            .build());

            return Flux.concat(Flux.just(citationsEvent), textEvents)
                    .doOnError(e -> {
                        degradationFlags.mark(DegradationFlags.CHAT);
                        // 失败也落库（截断版），保证历史会话不丢轮
                        conversationPersistence.recordAssistantMessage(conversationId,
                                acc.isEmpty() ? "[truncated: stream interrupted]" : acc + " [truncated]",
                                tenantId, platform);
                        persistTruncated(conversationId, acc.toString());
                    })
                    .doOnComplete(() -> {
                        degradationFlags.clear(DegradationFlags.CHAT);
                        String answer = acc.toString();
                        conversationPersistence.recordAssistantMessage(
                                conversationId, answer, tenantId, platform);
                        checkCitations(answer, rr.citations().size());
                        OutputGuardrailService.GuardrailResult guardrail =
                                runGuardrail(answer, contextOf(rr));
                        evaluateHandoff(conversationId, userMessage, state, rr,
                                guardrail, tenantId, platform);
                        recordRagEval(conversationId, userMessage, rr, systemPrompt, answer,
                                tenantId, platform);
                    });
        } catch (Exception e) {
            // 同步阶段异常（如检索失败）→ 以 SSE error 事件返回
            return GlobalExceptionHandler.toSseError(e);
        }
    }

    /**
     * RAG 线上指标采集（§9 评估台）：命中率 / 引用准确率 / 成本，全由真实流量产生。
     * 旁路调用——采集失败不影响对话。
     */
    private void recordRagEval(String conversationId, String query, RetrievalResult rr,
                               String systemPrompt, String answer,
                               String tenantId, String platform) {
        try {
            int docCount = rr.documents().size();
            int citationCount = CitationValidator.extractIndices(answer).size();
            int outOfRange = CitationValidator.outOfRange(answer, rr.citations().size()).size();
            int answerTokens = TokenUtils.estimateTokens(answer);
            int promptTokens = TokenUtils.estimateTokens(systemPrompt) + TokenUtils.estimateTokens(query);
            double cost = costCalculator.estimate("glm-5.2", promptTokens, answerTokens);
            ragEvalService.record(conversationId, query, tenantId, platform, docCount,
                    citationCount, outOfRange, answerTokens, cost);
        } catch (Exception e) {
            log.warn("RAG 评估采集失败: {}", e.getMessage());
        }
    }

    private Flux<ServerSentEvent<String>> clarificationFlux(String reason) {
        return Flux.just(ServerSentEvent.<String>builder().event("token").data(reason).build());
    }

    /** 状态提取失败时退化为空状态，不阻断对话。抽成方法以便 lambda 捕获（保持 effectively final）。 */
    private SessionState extractStateSafely(String conversationId, String userMessage) {
        try {
            return sessionStateService.extractState(conversationId, userMessage);
        } catch (Exception e) {
            log.warn("状态提取失败（best-effort 跳过）: {}", e.getMessage());
            return SessionState.empty(conversationId, TenantContext.get());
        }
    }

    /** citations 事件载荷：引用列表 + 当前降级中的能力（供前端显示"基础问答模式"）。 */
    public record CitationsPayload(List<Citation> citations, List<String> degraded) {
    }

    /** §5 后处理：校验 [n] 引用不越界（防模型编造引用），越界仅告警不阻断。 */
    private void checkCitations(String text, int maxIndex) {
        List<Integer> out = CitationValidator.outOfRange(text, maxIndex);
        if (!out.isEmpty()) {
            log.warn("回答含越界引用 [n]（n>{}）: {}", maxIndex, out);
        }
    }

    /** §1.1 输出护栏：越界强断言 + 事实一致性（LLM-as-judge）。返回判定结果供转人工决策。 */
    private OutputGuardrailService.GuardrailResult runGuardrail(String answer, String context) {
        boolean outOfScope = outputGuardrailService.isOutOfScope(answer);
        if (outOfScope) {
            log.warn("回答疑似越界强断言，建议转人工: {}", answer);
        }
        OutputGuardrailService.GuardrailResult result = outputGuardrailService.judge(answer, context);
        if (result.failed()) {
            log.warn("事实一致性 FAIL: {}", result.reason());
        }
        return outOfScope && !result.failed()
                ? new OutputGuardrailService.GuardrailResult("FAIL", "越界强断言且无引用")
                : result;
    }

    /**
     * 转人工判定（M3）：命中任一条件即建工单，带对话上下文交接给人工。
     *
     * <p>条件按优先级：用户明确要求 &gt; 情绪负面 &gt; 事实校验失败 &gt; 检索未命中。
     * 幂等由 {@code HandoffService} 保证，同一会话同一原因不会重复建单。
     */
    private void evaluateHandoff(String conversationId, String userMessage, SessionState state,
                                 RetrievalResult rr,
                                 OutputGuardrailService.GuardrailResult guardrail,
                                 String tenantId, String platform) {
        try {
            if (userMessage != null && HANDOFF_PATTERN.matcher(userMessage).find()) {
                handoffService.createIfNeeded(conversationId, HandoffTicket.Reason.USER_REQUEST,
                        "用户消息: " + userMessage, tenantId, platform);
            } else if (isNegativeEmotion(state.emotion())) {
                handoffService.createIfNeeded(conversationId, HandoffTicket.Reason.NEGATIVE_EMOTION,
                        "识别情绪: " + state.emotion(), tenantId, platform);
            } else if (guardrail != null && guardrail.failed()) {
                handoffService.createIfNeeded(conversationId, HandoffTicket.Reason.GUARDRAIL_FAIL,
                        guardrail.reason(), tenantId, platform);
            } else if (rr.documents().isEmpty()) {
                handoffService.createIfNeeded(conversationId, HandoffTicket.Reason.NO_HIT,
                        "未召回任何知识库文档，提问: " + userMessage, tenantId, platform);
            }
        } catch (Exception e) {
            log.warn("转人工判定失败: {}", e.getMessage());
        }
    }

    private static final java.util.regex.Pattern HANDOFF_PATTERN =
            java.util.regex.Pattern.compile("转人工|人工客服|找人工|人工服务|真人客服");

    private static boolean isNegativeEmotion(String emotion) {
        if (emotion == null || emotion.isBlank()) {
            return false;
        }
        String e = emotion.toLowerCase();
        return e.contains("愤怒") || e.contains("生气") || e.contains("投诉")
                || e.contains("不满") || e.contains("差评") || e.contains("失望")
                || e.contains("angry") || e.contains("upset");
    }

    private String contextOf(RetrievalResult rr) {
        StringBuilder sb = new StringBuilder();
        for (var d : rr.documents()) {
            sb.append(d.getText()).append('\n');
        }
        return sb.toString();
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
