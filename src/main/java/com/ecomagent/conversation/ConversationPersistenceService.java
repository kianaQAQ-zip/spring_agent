package com.ecomagent.conversation;

import com.ecomagent.common.DegradationFlags;
import com.ecomagent.common.PiiMaskUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话落库编排。
 *
 * <p>这是统计 / 检索 / 导出的<b>唯一数据入口</b>。职责：
 * <ol>
 *   <li>user 消息进入即落库（保证"用户问了什么"即使流式失败也不丢）；</li>
 *   <li>assistant 消息在流结束时落库（完整或截断版）；</li>
 *   <li>内容落库前做 PII 脱敏，并标记 {@code piiMasked}；</li>
 *   <li>落库失败<b>不阻断对话</b>，但必须 {@code mark(PERSISTENCE)}——否则又是静默失效。</li>
 * </ol>
 *
 * <p>落库是旁路：try-catch 吞掉异常换降级标记，而不是让用户看到 500。
 */
@Service
public class ConversationPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ConversationPersistenceService.class);
    private static final int TITLE_MAX = 50;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final DegradationFlags degradationFlags;

    public ConversationPersistenceService(ConversationRepository conversationRepository,
                                          MessageRepository messageRepository,
                                          DegradationFlags degradationFlags) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.degradationFlags = degradationFlags;
    }

    /**
     * 用户消息进入即落库；首次消息同时建会话记录并设标题。
     *
     * @param tenantId 显式传入——SSE 流式的回调跑在 Reactor 线程，ThreadLocal 读不到
     * @param platform 同上，必须显式传
     */
    public void recordUserMessage(String conversationId, String content,
                                  String tenantId, String platform) {
        try {
            conversationRepository.upsert(conversationId, tenantId, platform, titleOf(content));
            messageRepository.insert(conversationId, tenantId, platform, "user", content, false);
            degradationFlags.clear(DegradationFlags.PERSISTENCE);
        } catch (Exception e) {
            // 旁路失败：不阻断对话，但必须可观测
            degradationFlags.mark(DegradationFlags.PERSISTENCE);
            log.warn("会话落库失败（user 消息）: {}", e.getMessage());
        }
    }

    /** 助手消息流结束后落库（content 已脱敏）。tenant/platform 显式传入，不依赖 ThreadLocal。 */
    public void recordAssistantMessage(String conversationId, String content,
                                       String tenantId, String platform) {
        try {
            conversationRepository.touch(conversationId, tenantId);
            String masked = PiiMaskUtil.mask(content);
            messageRepository.insert(conversationId, tenantId, platform, "assistant",
                    masked, !masked.equals(content));
            degradationFlags.clear(DegradationFlags.PERSISTENCE);
        } catch (Exception e) {
            degradationFlags.mark(DegradationFlags.PERSISTENCE);
            log.warn("会话落库失败（assistant 消息）: {}", e.getMessage());
        }
    }

    private static String titleOf(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String t = content.trim().replaceAll("\\s+", " ");
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX) + "…";
    }
}
