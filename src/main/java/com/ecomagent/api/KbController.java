package com.ecomagent.api;

import com.ecomagent.common.ApiResponse;
import com.ecomagent.rag.DocChunk;
import com.ecomagent.rag.IngestionResult;
import com.ecomagent.rag.KbIngestionService;
import com.ecomagent.rag.KbManageService;
import com.ecomagent.rag.KnowledgeDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

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
    private final KbManageService manageService;

    public KbController(KbIngestionService ingestionService, KbManageService manageService) {
        this.ingestionService = ingestionService;
        this.manageService = manageService;
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

    // ==================== 知识库管理（M5 运营） ====================

    /** 文档分页列表：kbId/status/keyword 筛选 + 排序。 */
    @GetMapping("/documents")
    public ApiResponse<Map<String, Object>> documents(
            @RequestParam(required = false) String kbId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String order,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(manageService.listDocuments(kbId, status, keyword, sort, order, page, size));
    }

    /** 文档详情：元信息 + chunk 列表预览。 */
    @GetMapping("/documents/{docId}")
    public ApiResponse<Map<String, Object>> docDetail(@PathVariable String docId) {
        Map<String, Object> detail = manageService.docDetail(docId);
        if (detail == null) {
            return ApiResponse.fail(404, "未找到文档: " + docId);
        }
        return ApiResponse.ok(detail);
    }

    /** 删除文档（向量 + 元信息）。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/documents/{docId}")
    public ApiResponse<Boolean> deleteDoc(@PathVariable String docId) {
        boolean ok = manageService.deleteDocument(docId);
        if (!ok) {
            return ApiResponse.fail(404, "未找到文档: " + docId);
        }
        log.info("文档已删除: docId={}", docId);
        return ApiResponse.ok(true);
    }

    /** 重新处理：基于已解析全文重新分块 + 向量化（无需重新上传原文件）。 */
    @PostMapping("/documents/{docId}/reprocess")
    public ApiResponse<Map<String, Object>> reprocess(@PathVariable String docId) {
        try {
            int chunks = ingestionService.reprocess(docId);
            return ApiResponse.ok(Map.of("docId", docId, "chunkCount", chunks));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    /** 知识库列表（含文档数 / 总 chunks / 隔离数统计）。 */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> listKbs() {
        return ApiResponse.ok(manageService.listKbs());
    }

    /** 创建知识库。 */
    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createKb(@RequestBody Map<String, String> body) {
        try {
            return ApiResponse.ok(manageService.createKb(body.get("name"), body.get("description")));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    /** 删除知识库（默认知识库不可删；级联删除其中文档）。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/{kbId}")
    public ApiResponse<Boolean> deleteKb(@PathVariable String kbId) {
        try {
            return ApiResponse.ok(manageService.deleteKb(kbId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    /** 知识库总览统计。 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(manageService.stats());
    }

    /** 检索测试：向量召回预览（不走 LLM，零成本验证知识覆盖）。 */
    @PostMapping("/retrieval-test")
    public ApiResponse<List<Map<String, Object>>> retrievalTest(@RequestBody Map<String, Object> body) {
        String kbId = body.get("kbId") == null ? "" : String.valueOf(body.get("kbId"));
        String query = String.valueOf(body.getOrDefault("query", ""));
        int topK = body.get("topK") == null ? 5 : Integer.parseInt(String.valueOf(body.get("topK")));
        if (query.isBlank()) {
            return ApiResponse.fail(400, "测试问题不能为空");
        }
        return ApiResponse.ok(manageService.retrievalTest(kbId, query, topK));
    }
}
