package com.ecomagent.api;

import com.ecomagent.agent.ChatService;
import com.ecomagent.common.ApiResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对话接口（M1 仅 health 探活 + 验证 ChatClient 初始化）。
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        ChatClient client = chatService.getChatClient();
        boolean ready = client != null;
        return ApiResponse.ok(Map.of(
                "status", ready ? "UP" : "DOWN",
                "chatClient", ready ? "initialized" : "missing"
        ));
    }
}
