package com.ecomagent.agent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;

/**
 * 查询改写（§3.1 + §8.8.2）：L1 感知改写，<b>仅改检索句、不改原话</b>。
 *
 * <p>把「这个能退吗」结合上下文改写成含订单号的独立检索句，提升 RAG 命中。
 * Caffeine 缓存：key = MD5(近 3 轮摘要 + query)，TTL 10min，避免重复调用 LLM。
 */
@Service
public class QueryRewriteService {

    private static final int RECENT_MESSAGES = 6; // 近 3 轮 × (user + assistant)

    private final ChatModel qwenTurbo;
    private final ChatMemory chatMemory;
    private final Cache<String, String> cache;

    public QueryRewriteService(@Qualifier("qwenTurboChatModel") ChatModel qwenTurbo,
                               ChatMemory chatMemory) {
        this.qwenTurbo = qwenTurbo;
        this.chatMemory = chatMemory;
        this.cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();
    }

    public String rewrite(String conversationId, String query) {
        String key = cacheKey(conversationId, query);
        String cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        String rewritten = doRewrite(conversationId, query);
        cache.put(key, rewritten);
        return rewritten;
    }

    private String doRewrite(String conversationId, String query) {
        List<Message> history = chatMemory.get(conversationId);
        String recent = history == null ? "" : history.stream()
                .map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);

        String system = """
                你是检索查询改写器。结合对话上下文，把用户口语改写成一条独立、含订单号/关键词的检索查询。
                只输出改写后的查询句，不要解释。若无需改写，直接输出原句。
                """;
        String user = "对话历史（近几轮）：\n" + recent + "\n\n用户本轮消息：" + query;

        try {
            ChatResponse resp = qwenTurbo.call(new Prompt(
                    List.of(new SystemMessage(system), new UserMessage(user))));
            String rewritten = resp.getResult().getOutput().getText().trim();
            return rewritten.isBlank() ? query : rewritten;
        } catch (Exception e) {
            // 改写失败回退原查询，不阻断检索
            return query;
        }
    }

    private String cacheKey(String conversationId, String query) {
        List<Message> history = chatMemory.get(conversationId);
        StringBuilder recent = new StringBuilder();
        if (history != null) {
            int from = Math.max(0, history.size() - RECENT_MESSAGES);
            for (int i = from; i < history.size(); i++) {
                recent.append(history.get(i).getText()).append('|');
            }
        }
        return md5(recent + query);
    }

    private String md5(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return raw;
        }
    }
}
