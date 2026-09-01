package com.ecomagent.api;

import com.ecomagent.common.ApiResponse;
import com.ecomagent.eval.KbGapService;
import com.ecomagent.eval.RagEvalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RAG 效果评估台接口（L2）：命中率 / 引用准确率 / 成本趋势 / 知识库缺口，全由真实对话产生。
 */
@RestController
@RequestMapping("/eval")
public class EvalController {

    private final RagEvalService ragEvalService;
    private final KbGapService kbGapService;

    public EvalController(RagEvalService ragEvalService, KbGapService kbGapService) {
        this.ragEvalService = ragEvalService;
        this.kbGapService = kbGapService;
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(ragEvalService.summary());
    }

    @GetMapping("/trend")
    public ApiResponse<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "14") int days) {
        return ApiResponse.ok(ragEvalService.dailyTrend(Math.min(Math.max(days, 1), 90)));
    }

    /** 知识库缺口：未命中问题 Top N（该补哪些文档，按真实提问频次排序）。 */
    @GetMapping("/gaps")
    public ApiResponse<List<Map<String, Object>>> gaps(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(kbGapService.topGaps(days, limit));
    }

    /** 缺口总览：未命中对话数 / 不同问法数。 */
    @GetMapping("/gaps/summary")
    public ApiResponse<Map<String, Object>> gapSummary(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(kbGapService.gapSummary(days));
    }
}
