package com.ecomagent.eval;

/**
 * 单条用例判分（§9 四维：关键词覆盖 / 意图正确 / 引用接地 / 忠实度）。
 */
public record CaseScore(
        String id,
        String category,
        double keyword,
        double intent,
        double grounding,
        double faithfulness,
        double total,
        boolean pass) {
}
