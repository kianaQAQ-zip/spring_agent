package com.ecomagent.eval;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Token 成本计算（§9 可观测，框架原生不落表）。
 *
 * <p>按每百万 token 价格估算单轮成本，供 {@code CostTrackingAdvisor} 汇总。
 * 价格为演示示例，生产应从价格中心/配置读取。
 */
@Component
public class CostCalculator {

    // 每百万 token 价格（元）：[输入价, 输出价]
    private static final Map<String, double[]> PRICE_PER_MILLION = Map.of(
            "qwen-plus", new double[]{0.8, 2.0},
            "qwen-turbo", new double[]{0.3, 0.6});

    public double estimate(String model, int promptTokens, int completionTokens) {
        double[] p = PRICE_PER_MILLION.getOrDefault(model, new double[]{0.8, 2.0});
        return promptTokens / 1_000_000.0 * p[0] + completionTokens / 1_000_000.0 * p[1];
    }
}
