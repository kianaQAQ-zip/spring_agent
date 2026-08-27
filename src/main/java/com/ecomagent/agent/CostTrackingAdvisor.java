package com.ecomagent.agent;

import com.ecomagent.eval.CostCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 成本追踪 Advisor（§9 可观测）：每轮汇总 token 用量与估算成本。
 *
 * <p>用 Spring AI 原生 Advisor 机制（不自建表）；仅从响应 metadata 的 {@link Usage} 读取并打日志，
 * 与 Spring AI 自带的 Micrometer/OTel trace（observation）互补。
 */
@Component
public class CostTrackingAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(CostTrackingAdvisor.class);
    private static final String MODEL = "qwen-plus";

    private final CostCalculator costCalculator;

    public CostTrackingAdvisor(CostCalculator costCalculator) {
        this.costCalculator = costCalculator;
    }

    @Override
    public String getName() {
        return "CostTracking";
    }

    @Override
    public int getOrder() {
        // 最外层，包住 Memory Advisor 与模型调用，捕获最终 token 用量
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        track(response.chatResponse());
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request).doOnNext(r -> track(r.chatResponse()));
    }

    private void track(ChatResponse response) {
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return;
        }
        int prompt = nz(usage.getPromptTokens());
        int completion = nz(usage.getCompletionTokens());
        double cost = costCalculator.estimate(MODEL, prompt, completion);
        log.info("token cost: prompt={} completion={} est=¥{}",
                prompt, completion, String.format("%.6f", cost));
    }

    private int nz(Integer i) {
        return i == null ? 0 : i;
    }
}
