package com.ecomagent.api;

import com.ecomagent.common.ApiResponse;
import com.ecomagent.eval.RagEvalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RAG 效果评估台接口（L2）：命中率 / 引用准确率 / 成本趋势，全由真实对话产生。
 */
@RestController
@RequestMapping("/eval")
public class EvalController {

    private final RagEvalService ragEvalService;

    public EvalController(RagEvalService ragEvalService) {
        this.ragEvalService = ragEvalService;
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(ragEvalService.summary());
    }

    @GetMapping("/trend")
    public ApiResponse<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "14") int days) {
        return ApiResponse.ok(ragEvalService.dailyTrend(Math.min(Math.max(days, 1), 90)));
    }
}
