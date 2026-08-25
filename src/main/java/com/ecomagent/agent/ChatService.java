package com.ecomagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 对话编排服务（M1 仅占位初始化，后续里程碑接入 Advisor 链 + 流式）。
 */
@Service
public class ChatService {

    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder builder) {
        // M1：空 ChatClient，仅验证多模型 Bean 可被注入构造
        this.chatClient = builder.build();
    }

    public ChatClient getChatClient() {
        return chatClient;
    }
}
