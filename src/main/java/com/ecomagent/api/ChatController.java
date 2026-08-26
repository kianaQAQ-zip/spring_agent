package com.ecomagent.api;

import com.ecomagent.agent.ChatService;
import com.ecomagent.common.ApiResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 对话接口（M1 health 探活 + M3 流式对话）。
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

    /**
     * 流式对话（SSE，M3）。逐 token 以 {@code event: token} 推送，前端 EventSource 直接消费。
     *
     * @param message        用户消息
     * @param conversationId 会话 ID（记忆窗口键，默认 "default"）
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestParam("message") String message,
            @RequestParam(value = "conversationId", defaultValue = "default") String conversationId) {
        return chatService.streamAnswer(conversationId, message);
    }
}
