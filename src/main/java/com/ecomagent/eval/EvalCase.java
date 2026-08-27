package com.ecomagent.eval;

import java.util.List;

/**
 * 评估用例（§9 评估集）：一条查询 + 判分依据。
 */
public record EvalCase(
        String id,
        String category,
        String query,
        List<String> keywords,
        String expectedIntent) {
}
