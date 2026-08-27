package com.ecomagent.agent;

import com.ecomagent.eval.CostCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * 成本追踪 Advisor（§9 可观测）：每轮汇总 token 用量与估算成本。
 *
 * <p>用 Spring AI 原生 {@link BaseAdvisor} 的 before/after 钩子（不自建表）：
 * 在 {@code after} 阶段从响应 metadata 的 {@link Usage} 读 token 数，经 {@link CostCalculator}
 * 估成本后打日志；与 Spring AI 自带的 Micrometer/OTel observation 互补。
 */
@Component
public class CostTrackingAdvisor implements BaseAdvisor {

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
        // 内部 ChatModelStreamAdvisor 用 LOWEST_PRECEDENCE 调模型；本 advisor 用 0 包在模型之外，
        // 使 after 阶段能拿到最终响应的 token 用量。
        return 0;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        track(response.chatResponse());
        return response;
    }

    private void track(ChatResponse response) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
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
