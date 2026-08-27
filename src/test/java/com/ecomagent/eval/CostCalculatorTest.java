package com.ecomagent.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 成本计算单测（§9 可观测）。
 */
class CostCalculatorTest {

    private final CostCalculator calculator = new CostCalculator();

    @Test
    void estimatesCostByModel() {
        // qwen-plus：输入 0.8 元/百万，输出 2.0 元/百万
        double cost = calculator.estimate("qwen-plus", 1_000_000, 1_000_000);
        assertEquals(2.8, cost, 0.001);
    }

    @Test
    void scalesWithTokens() {
        // 各 50 万 token → 半价
        double cost = calculator.estimate("qwen-plus", 500_000, 500_000);
        assertEquals(1.4, cost, 0.001);
    }
}
