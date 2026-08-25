package com.ecomagent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 多模型 Bean 装配（§0）。
 * 阿里系（qwen-plus / qwen-turbo / text-embedding-v3）统一走阿里云百炼 DashScope OpenAI 兼容接口；
 * DeepSeek-V3 属独立供应商，base-url 不同，二者均经 OpenAI 兼容协议，可互降。
 * API Key 全部从环境变量注入，不落库（§5 合规）。
 */
@Configuration
public class ModelConfig {

    private static final String DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";

    @Bean
    @Qualifier("qwenChatModel")
    public ChatModel qwenChatModel(@Value("${dashscope.api-key:}") String apiKey) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DASHSCOPE_BASE_URL)
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("qwen-plus")
                        .temperature(0.7)
                        .build())
                .build();
    }

    /** 轻量模型：供 QueryRewrite / OutputGuardrail 的判定调用（低价、低延迟） */
    @Bean
    @Qualifier("qwenTurboChatModel")
    public ChatModel qwenTurboChatModel(@Value("${dashscope.api-key:}") String apiKey) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DASHSCOPE_BASE_URL)
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("qwen-turbo")
                        .temperature(0.2)
                        .build())
                .build();
    }

    /** 降级 LLM：DeepSeek-V3 */
    @Bean
    @Qualifier("deepSeekChatModel")
    public ChatModel deepSeekChatModel(@Value("${deepseek.api-key:}") String apiKey) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DEEPSEEK_BASE_URL)
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-chat")
                        .temperature(0.7)
                        .build())
                .build();
    }

    /** Embedding：通义 text-embedding-v3，维度 1024（与 vector_store.embedding 严格一致） */
    @Bean
    public EmbeddingModel embeddingModel(@Value("${dashscope.api-key:}") String apiKey) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DASHSCOPE_BASE_URL)
                .apiKey(apiKey)
                .build();
        return new OpenAiEmbeddingModel(
                api,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model("text-embedding-v3")
                        .build());
    }
}
