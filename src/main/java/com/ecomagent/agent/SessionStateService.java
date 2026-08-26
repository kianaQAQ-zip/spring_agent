package com.ecomagent.agent;

import com.ecomagent.common.TenantContext;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * L2 会话状态提取（§8 Layer 2）。
 *
 * <p>用 qwen-turbo（轻量低成本）+ {@code BeanOutputConverter<SessionStateDelta>} 做<b>增量</b>提取，
 * 只覆盖非空字段（替换而非并存，§8.8.1）。写回走 {@link SessionStateRepository} 乐观锁 +
 * {@link SessionWriteLock} 同 session 串行（§8.8.3）。
 */
@Service
public class SessionStateService {

    private final ChatModel qwenTurbo;
    private final SessionStateRepository repository;
    private final SessionWriteLock writeLock;

    public SessionStateService(@Qualifier("qwenTurboChatModel") ChatModel qwenTurbo,
                               SessionStateRepository repository,
                               SessionWriteLock writeLock) {
        this.qwenTurbo = qwenTurbo;
        this.repository = repository;
        this.writeLock = writeLock;
    }

    public SessionState extractState(String conversationId, String userMessage) {
        return writeLock.withLock(conversationId, () -> {
            SessionState current = repository.findBySessionId(conversationId);
            if (current == null) {
                current = SessionState.empty(conversationId, TenantContext.get());
                repository.insert(current);
            }
            SessionStateDelta delta = extractDelta(current, userMessage);
            SessionState next = apply(current, delta);
            if (!repository.update(next)) {
                // 乐观锁冲突：重读后重试一次
                SessionState fresh = repository.findBySessionId(conversationId);
                if (fresh == null) {
                    return next;
                }
                SessionState retried = apply(fresh, delta);
                repository.update(retried);
                return retried;
            }
            return next;
        });
    }

    private SessionStateDelta extractDelta(SessionState current, String userMessage) {
        BeanOutputConverter<SessionStateDelta> converter = new BeanOutputConverter<>(SessionStateDelta.class);
        String system = """
                你是电商客服会话状态提取器。从用户消息中增量提取结构化状态，只输出 JSON，格式：
                %s
                要求：intent 取值 ORDER_QUERY/REFUND/ADDRESS_CHANGE/COUPON/KNOWLEDGE_QA/CHITCHAT；
                orderId 为订单号（如 ORD-1001）；emotion 为用户情绪词（无则空）。无变化时 noChange=true。
                当前状态：intent=%s, orderId=%s, emotion=%s
                """.formatted(converter.getFormat(), current.intent(), current.orderId(), current.emotion());
        String user = "用户消息：" + userMessage;

        try {
            ChatResponse resp = qwenTurbo.call(new Prompt(
                    List.of(new SystemMessage(system), new UserMessage(user))));
            String content = resp.getResult().getOutput().getText();
            return converter.convert(content);
        } catch (Exception e) {
            // LLM 失败/解析失败：保守返回 noChange，不阻塞对话
            return new SessionStateDelta(null, null, null, true);
        }
    }

    private SessionState apply(SessionState current, SessionStateDelta delta) {
        if (delta.isNoChange()) {
            return current;
        }
        String intent = pick(delta.intent(), current.intent());
        String orderId = pick(delta.orderId(), current.orderId());
        String emotion = pick(delta.emotion(), current.emotion());
        return new SessionState(current.sessionId(), current.tenantId(),
                intent, orderId, emotion, current.version(), Instant.now());
    }

    private String pick(String delta, String current) {
        return (delta == null || delta.isBlank()) ? current : delta;
    }
}
