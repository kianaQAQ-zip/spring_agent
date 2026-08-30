package com.ecomagent.common;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 降级 ChatModel 包装：主模型失败时按序切到备用模型。
 *
 * <p>只有当备用模型的 key 配了才会被 {@code ModelConfig} 装上；未配置时走裸模型，行为完全一致。
 *
 * <p>流式降级有个坑：主模型已经吐了部分 token 才失败的话，切到备用模型会导致输出重复。
 * 所以只在<b>第一个 token 之前</b>失败时才切换——已经吐过就原样抛错，让上层走 SSE error 事件。
 */
public class FallbackChatModel implements ChatModel {

    private final ChatModel primary;
    private final List<ChatModel> fallbacks;
    private final DegradationFlags flags;
    private final String capability;

    public FallbackChatModel(ChatModel primary, List<ChatModel> fallbacks,
                             DegradationFlags flags, String capability) {
        this.primary = primary;
        this.fallbacks = (fallbacks == null) ? List.of() : List.copyOf(fallbacks);
        this.flags = flags;
        this.capability = capability;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            ChatResponse r = primary.call(prompt);
            clear();
            return r;
        } catch (RuntimeException primaryError) {
            for (ChatModel fb : fallbacks) {
                try {
                    return fb.call(prompt);
                } catch (RuntimeException ignored) {
                    // 换下一个
                }
            }
            mark();
            throw primaryError;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        AtomicBoolean started = new AtomicBoolean(false);

        Flux<ChatResponse> chain = primary.stream(prompt)
                .doOnNext(r -> started.set(true));

        for (ChatModel fb : fallbacks) {
            // started 为真说明已经推过 token，再切模型会重复输出
            chain = chain.onErrorResume(e ->
                    started.get() ? Flux.error(e) : fb.stream(prompt));
        }

        return chain
                .doOnComplete(this::clear)
                .doOnError(e -> mark());
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return primary.getDefaultOptions();
    }

    private void mark() {
        if (flags != null) {
            flags.mark(capability);
        }
    }

    private void clear() {
        if (flags != null) {
            flags.clear(capability);
        }
    }
}
