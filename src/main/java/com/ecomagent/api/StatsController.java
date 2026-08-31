package com.ecomagent.api;

import com.ecomagent.common.ApiResponse;
import com.ecomagent.conversation.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 统计看板接口（L2）：多平台咨询态势的聚合数据源。
 */
@RestController
@RequestMapping("/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(statsService.overview());
    }

    @GetMapping("/trend")
    public ApiResponse<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "14") int days) {
        return ApiResponse.ok(statsService.trend(Math.min(Math.max(days, 1), 90)));
    }

    @GetMapping("/platform")
    public ApiResponse<List<Map<String, Object>>> platform() {
        return ApiResponse.ok(statsService.platformDist());
    }

    @GetMapping("/hourly")
    public ApiResponse<List<Map<String, Object>>> hourly() {
        return ApiResponse.ok(statsService.hourlyDist());
    }

    @GetMapping("/intent")
    public ApiResponse<List<Map<String, Object>>> intent() {
        return ApiResponse.ok(statsService.intentDist());
    }
}
