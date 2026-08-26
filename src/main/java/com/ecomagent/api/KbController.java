package com.ecomagent.api;

import com.ecomagent.common.ApiResponse;
import com.ecomagent.rag.DocChunk;
import com.ecomagent.rag.IngestionResult;
import com.ecomagent.rag.KbIngestionService;
import com.ecomagent.rag.KnowledgeDoc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库管理端点（M2 上传 + M5 溯源）。
 *
 * <ul>
 *   <li>POST /kb/upload：上传文档 → 解析 → 分块 → 向量化 → 入库。</li>
 *   <li>GET  /kb/doc/{docId}：取回文档元信息与解析全文。</li>
 *   <li>GET  /kb/doc/{docId}?chunk={chunkIndex}：全文 + 定位 chunk（引用溯源源抽屉）。</li>
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
    public ApiResponse<?> getDoc(@PathVariable String docId,
                                 @RequestParam(value = "chunk", required = false) Integer chunk) {
        if (chunk == null) {
            KnowledgeDoc doc = ingestionService.getDoc(docId);
            if (doc == null) {
                return ApiResponse.fail(404, "未找到文档: " + docId);
            }
            return ApiResponse.ok(doc);
        }
        DocChunk docChunk = ingestionService.getChunk(docId, chunk);
        if (docChunk == null) {
            return ApiResponse.fail(404, "未找到文档: " + docId);
        }
        return ApiResponse.ok(docChunk);
    }
}
