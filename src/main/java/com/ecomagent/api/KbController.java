package com.ecomagent.api;

import com.ecomagent.common.ApiResponse;
import com.ecomagent.rag.DocChunk;
import com.ecomagent.rag.IngestionResult;
import com.ecomagent.rag.KbIngestionService;
import com.ecomagent.rag.KnowledgeDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    private static final Logger log = LoggerFactory.getLogger(KbController.class);

    private final KbIngestionService ingestionService;

    public KbController(KbIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/upload")
    public ApiResponse<IngestionResult> upload(@RequestParam("file") MultipartFile file) {
        String filename = file == null ? null : file.getOriginalFilename();
        long size = file == null ? 0 : file.getSize();
        log.info("文档上传开始: file={} size={}B", filename, size);
        if (file == null || file.isEmpty()) {
            log.warn("文档上传被拒绝: 文件为空 file={}", filename);
            return ApiResponse.fail(400, "上传文件为空");
        }
        try {
            IngestionResult result = ingestionService.ingest(file);
            log.info("文档上传完成: file={} docId={} status={} chunks={} cleanScore={}",
                    filename, result.docId(), result.status(), result.chunkCount(), result.cleanScore());
            return ApiResponse.ok(result);
        } catch (Exception e) {
            log.error("文档上传异常: file={} size={}B", filename, size, e);
            throw e;
        }
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

    @GetMapping("/doc/{docId}/export")
    public ResponseEntity<?> export(@PathVariable String docId,
                                    @RequestParam(value = "format", defaultValue = "md") String format) {
        KbIngestionService.DocExport r = ingestionService.exportDoc(docId, format);
        if (r == null) {
            return ResponseEntity.ok(ApiResponse.fail(400, "导出失败：文档不存在、doc-processor 不可达或格式不支持（txt/md/docx/xlsx/pdf）"));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(r.contentType()))
                .header("Content-Disposition", "attachment; filename=\"" + docId + "." + r.ext() + "\"")
                .body(r.bytes());
    }
}
