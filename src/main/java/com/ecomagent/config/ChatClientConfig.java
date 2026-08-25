package com.ecomagent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础 ChatClient Bean（M1 仅初始化，M3 起注入 Advisor 链 + 流式 + 记忆）。
 * 关键：项目注入了多个 ChatModel（qwen-plus / qwen-turbo / deepseek），
 * 必须显式提供 ChatClient.Builder 并使用 @Qualifier 锁定主模型，
 * 否则 Spring AI 自动配置的 Builder 因「ChatModel 不唯一」而启动失败。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient.Builder chatClientBuilder(@Qualifier("qwenChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    @Bean
    public ChatClient chatClient(@Qualifier("qwenChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
