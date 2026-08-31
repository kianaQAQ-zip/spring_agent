package com.ecomagent.api;

import com.ecomagent.conversation.ReportExporter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报表导出接口（L2，Q4 运营向）：对话明细 CSV。
 */
@RestController
@RequestMapping("/export")
public class ExportController {

    private final ReportExporter reportExporter;

    public ExportController(ReportExporter reportExporter) {
        this.reportExporter = reportExporter;
    }

    @GetMapping("/conversations.csv")
    public ResponseEntity<byte[]> exportConversations(
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        byte[] csv = reportExporter.exportConversations(platform, keyword, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"conversations.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(csv);
    }
}
