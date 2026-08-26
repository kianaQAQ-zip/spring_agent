package com.ecomagent.api;

import com.ecomagent.common.ApiResponse;
import com.ecomagent.rag.IngestionResult;
import com.ecomagent.rag.KbIngestionService;
import com.ecomagent.rag.KnowledgeDoc;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库管理端点（M2）。
 *
 * <ul>
 *   <li>POST /kb/upload：上传文档 → 解析 → 分块 → 向量化 → 入库。</li>
 *   <li>GET  /kb/doc/{docId}：取回文档元信息与解析全文（M5 引用溯源展示原文）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/kb")
public class KbController {

    private final KbIngestionService ingestionService;

    public KbController(KbIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/upload")
    public ApiResponse<IngestionResult> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail(400, "上传文件为空");
        }
        IngestionResult result = ingestionService.ingest(file);
        return ApiResponse.ok(result);
    }

    @GetMapping("/doc/{docId}")
    public ApiResponse<KnowledgeDoc> getDoc(@PathVariable String docId) {
        KnowledgeDoc doc = ingestionService.getDoc(docId);
        if (doc == null) {
            return ApiResponse.fail(404, "未找到文档: " + docId);
        }
        return ApiResponse.ok(doc);
    }
}
