package com.ecomagent.rag;

import com.ecomagent.rag.dto.ParseResult;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库入库编排（M2 核心）。
 *
 * <p>流程：doc-processor 解析 →（不可达则 Tika 兜底）→ clean_score 过低隔离 →
 * 柔性分块 → PgVectorStore 入库（metadata 带 tenant/doc_id/chunk_index/...）→ 写 knowledge_doc。
 *
 * <p>约定：单租户默认 {@code default}（M9 收口多租户接缝）。
 */
@Service
public class KbIngestionService {

    private final DocProcessorClient docProcessorClient;
    private final TikaDocumentLoader tikaDocumentLoader;
    private final StructureAwareChunker chunker;
    private final VectorStore vectorStore;
    private final KnowledgeDocRepository knowledgeDocRepository;
    private final Bm25Index bm25Index;

    private static final String DEFAULT_TENANT = "default";
    private static final double CLEAN_SCORE_THRESHOLD = 0.5;

    public KbIngestionService(DocProcessorClient docProcessorClient,
                              TikaDocumentLoader tikaDocumentLoader,
                              StructureAwareChunker chunker,
                              VectorStore vectorStore,
                              KnowledgeDocRepository knowledgeDocRepository,
                              Bm25Index bm25Index) {
        this.docProcessorClient = docProcessorClient;
        this.tikaDocumentLoader = tikaDocumentLoader;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.knowledgeDocRepository = knowledgeDocRepository;
        this.bm25Index = bm25Index;
    }

    public IngestionResult ingest(MultipartFile file) {
        String docId = UUID.randomUUID().toString();
        String filename = file.getOriginalFilename();

        ParseResult parse = docProcessorClient.parse(file);
        if (!parse.reachable) {
            // 故障隔离：doc-processor 不可达 → Tika 兜底，不阻断主流程（§10.5）
            parse = tikaDocumentLoader.parse(file);
        }

        // clean_score 过低 → 隔离复核，不入库（§9.1 质量门禁）
        if (parse.cleanScore < CLEAN_SCORE_THRESHOLD) {
            return new IngestionResult(docId, filename, "QUARANTINED", 0,
                    parse.cleanScore, parse.flags);
        }

        List<Chunk> chunks = chunker.chunk(docId, filename, parse.blocks);

        // 构建 Documents（PgVectorStore.add 内部用 Tongyi embedding bean 编码）
        List<Document> documents = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            Map<String, Object> metadata = Map.of(
                    "tenant_id", DEFAULT_TENANT,
                    "doc_id", c.docId(),
                    "chunk_index", c.chunkIndex(),
                    "source", c.source(),
                    "page", c.page(),
                    "block_type", c.blockType(),
                    "atomic", c.atomic(),
                    "token_count", c.tokenCount(),
                    "heading_path", c.headingPath());
            documents.add(new Document(c.content(), metadata));
        }
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
            // M5：同步写入过程内 BM25 索引，供混合召回关键词命中
            for (Document d : documents) {
                bm25Index.index(d);
            }
        }

        knowledgeDocRepository.save(new KnowledgeDoc(
                UUID.randomUUID().toString(), docId, DEFAULT_TENANT, filename,
                chunks.size(), parse.parsedText));

        return new IngestionResult(docId, filename, "INGESTED", chunks.size(),
                parse.cleanScore, parse.flags);
    }

    public KnowledgeDoc getDoc(String docId) {
        return knowledgeDocRepository.findByDocId(docId);
    }

    /** M5 溯源：取回文档全文 + 定位指定 chunk（经向量库 metadata 过滤）。 */
    public DocChunk getChunk(String docId, int chunkIndex) {
        KnowledgeDoc doc = getDoc(docId);
        if (doc == null) {
            return null;
        }
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression filter = b.and(
                b.eq("doc_id", docId),
                b.eq("chunk_index", chunkIndex)).build();
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(docId)
                .topK(1)
                .filterExpression(filter)
                .build());
        String chunkContent = docs.isEmpty() ? null : docs.get(0).getText();
        return new DocChunk(docId, doc.source(), doc.parsedText(), chunkIndex, chunkContent);
    }

    /** 多格式导出：txt/md 本地直出；docx/xlsx/pdf 经 doc-processor。失败返回 null。 */
    public DocExport exportDoc(String docId, String format) {
        KnowledgeDoc doc = getDoc(docId);
        if (doc == null || doc.parsedText() == null) {
            return null;
        }
        String text = doc.parsedText();
        String fmt = (format == null ? "md" : format.trim().toLowerCase());
        if ("txt".equals(fmt)) {
            return new DocExport(text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", "txt");
        }
        if ("md".equals(fmt)) {
            return new DocExport(text.getBytes(StandardCharsets.UTF_8), "text/markdown; charset=utf-8", "md");
        }
        DocProcessorClient.ExportResult r = docProcessorClient.export(text, fmt);
        if (r == null) {
            return null;
        }
        return new DocExport(r.bytes(), r.contentType(), fmt);
    }

    /** 导出结果：字节 + content-type + 扩展名。 */
    public record DocExport(byte[] bytes, String contentType, String ext) {
    }
}
