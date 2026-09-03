package com.ecomagent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Qualifier;
import com.ecomagent.common.DegradationFlags;
import com.ecomagent.common.FallbackChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 多模型 Bean 装配（§0）。
 * 阿里系（glm-5.2 / qwen-turbo / text-embedding-v3）统一走阿里云百炼 DashScope OpenAI 兼容接口；
 * DeepSeek-V3 属独立供应商，base-url 不同，二者均经 OpenAI 兼容协议，可互降。
 * API Key 全部从环境变量注入，不落库（§5 合规）。
 */
@Configuration
public class ModelConfig {

    private static final String DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";

    /**
     * 共享重试策略。
     *
     * <p>Spring AI 默认策略对 {@link NonTransientAiException}（403 额度耗尽、401 key 无效）
     * 也照重试——这类错误是永久性的，重试到天亮也不会成功，只会让延迟和日志噪音翻倍。
     * 这里显式排除，只对瞬时错误（连接超时、5xx、429 限流）做指数退避重试。
     */
    @Bean
    public RetryTemplate aiRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .notRetryOn(NonTransientAiException.class)
                .exponentialBackoff(Duration.ofMillis(400), 2, Duration.ofSeconds(4))
                .build();
    }

    @Bean
    @Qualifier("qwenChatModel")
    public ChatModel qwenChatModel(@Value("${dashscope.api-key:}") String apiKey,
                                    RetryTemplate retryTemplate,
                                    @Value("${deepseek.api-key:}") String deepSeekKey,
                                    @Qualifier("deepSeekChatModel") ChatModel deepSeek,
                                    DegradationFlags flags) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DASHSCOPE_BASE_URL)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .build();
        ChatModel primary = OpenAiChatModel.builder()
                .openAiApi(api)
                .retryTemplate(retryTemplate)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("glm-5.2")
                        .temperature(0.7)
                        .build())
                .build();
        return withFallback(primary, deepSeekKey, deepSeek, flags, DegradationFlags.CHAT);
    }

    /**
     * 轻量模型：供 QueryRewrite / OutputGuardrail / 状态提取的判定调用（低温度）。
     *
     * <p>2026-09-03 起从 qwen-turbo 改为 glm-5.2：qwen-turbo 走免费额度池，
     * 额度耗尽后返回 403 AllocationQuota.FreeTierOnly，导致 query-rewrite / state-extract /
     * guardrail 三个能力全部静默失效。glm-5.2 不受该免费池限制（主对话实测可用）。
     */
    @Bean
    @Qualifier("qwenTurboChatModel")
    public ChatModel qwenTurboChatModel(@Value("${dashscope.api-key:}") String apiKey,
                                         RetryTemplate retryTemplate,
                                         @Value("${deepseek.api-key:}") String deepSeekKey,
                                         @Qualifier("deepSeekChatModel") ChatModel deepSeek,
                                         DegradationFlags flags) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DASHSCOPE_BASE_URL)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .build();
        ChatModel primary = OpenAiChatModel.builder()
                .openAiApi(api)
                .retryTemplate(retryTemplate)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("glm-5.2")
                        .temperature(0.2)
                        .build())
                .build();
        return withFallback(primary, deepSeekKey, deepSeek, flags, DegradationFlags.STATE_EXTRACT);
    }

    /**
     * 备用 key 配了才装降级链；没配就原样返回，零开销、行为不变。
     *
     * <p>注意：主模型和备用模型若共用同一账户的免费额度池（比如都是百炼），
     * 额度耗尽时切过去照样 403——那种情况下降级链只是多打一次无谓请求。
     */
    private static ChatModel withFallback(ChatModel primary, String fallbackKey,
                                          ChatModel fallback, DegradationFlags flags,
                                          String capability) {
        if (fallbackKey == null || fallbackKey.isBlank()) {
            return primary;
        }
        return new FallbackChatModel(primary, List.of(fallback), flags, capability);
    }

    /** 降级 LLM：DeepSeek-V3 */
    @Bean
    @Qualifier("deepSeekChatModel")
    public ChatModel deepSeekChatModel(@Value("${deepseek.api-key:}") String apiKey,
                                        RetryTemplate retryTemplate) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DEEPSEEK_BASE_URL)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .retryTemplate(retryTemplate)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-chat")
                        .temperature(0.7)
                        .build())
                .build();
    }

    /** Embedding：通义 text-embedding-v3，维度 1024（与 vector_store.embedding 严格一致） */
    @Bean
    public EmbeddingModel embeddingModel(@Value("${dashscope.api-key:}") String apiKey,
                                          RetryTemplate retryTemplate) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DASHSCOPE_BASE_URL)
                .apiKey(apiKey)
                .embeddingsPath("/embeddings")
                .build();
        return new OpenAiEmbeddingModel(
                api,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model("text-embedding-v3")
                        .build(),
                retryTemplate);
    }
}
