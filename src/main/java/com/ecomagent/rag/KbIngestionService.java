package com.ecomagent.rag;

import com.ecomagent.rag.dto.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(KbIngestionService.class);

    private final DocProcessorClient docProcessorClient;
    private final TikaDocumentLoader tikaDocumentLoader;
    private final StructureAwareChunker chunker;
    private final VectorStore vectorStore;
    private final KnowledgeDocRepository knowledgeDocRepository;
    private final Bm25Index bm25Index;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final String DEFAULT_TENANT = "default";
    private static final double CLEAN_SCORE_THRESHOLD = 0.5;

    public KbIngestionService(DocProcessorClient docProcessorClient,
                              TikaDocumentLoader tikaDocumentLoader,
                              StructureAwareChunker chunker,
                              VectorStore vectorStore,
                              KnowledgeDocRepository knowledgeDocRepository,
                              Bm25Index bm25Index,
                              org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.docProcessorClient = docProcessorClient;
        this.tikaDocumentLoader = tikaDocumentLoader;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.knowledgeDocRepository = knowledgeDocRepository;
        this.bm25Index = bm25Index;
        this.jdbcTemplate = jdbcTemplate;
    }

    public IngestionResult ingest(MultipartFile file) {
        String docId = UUID.randomUUID().toString();
        String filename = file.getOriginalFilename();

        // 去重：同源文件重复上传会堆积重复向量，先删旧文档再入库（覆盖语义）
        dedupeBySource(filename);

        ParseResult parse = docProcessorClient.parse(file);
        if (!parse.reachable) {
            // 故障隔离：doc-processor 不可达 → Tika 兜底，不阻断主流程（§10.5）
            parse = tikaDocumentLoader.parse(file);
        }

        // clean_score 过低 → 隔离复核，不入向量库（§9.1 质量门禁）；但元信息落库（状态 QUARANTINED）供失败列表排查
        if (parse.cleanScore < CLEAN_SCORE_THRESHOLD) {
            knowledgeDocRepository.save(new KnowledgeDoc(
                    UUID.randomUUID().toString(), docId, DEFAULT_TENANT, filename,
                    0, parse.parsedText, "default", file.getSize(), KnowledgeDoc.STATUS_QUARANTINED,
                    parse.cleanScore));
            log.warn("文档被隔离（cleanScore={}）: docId={} source={}", parse.cleanScore, docId, filename);
            return new IngestionResult(docId, filename, "QUARANTINED", 0,
                    parse.cleanScore, parse.flags);
        }

        List<Chunk> chunks = chunker.chunk(docId, filename, parse.blocks);
        int indexed = indexChunks(chunks, "default");

        knowledgeDocRepository.save(new KnowledgeDoc(
                UUID.randomUUID().toString(), docId, DEFAULT_TENANT, filename,
                chunks.size(), parse.parsedText, "default", file.getSize(),
                KnowledgeDoc.STATUS_INGESTED, parse.cleanScore));

        log.info("知识库入库成功: docId={} source={} chunks={} cleanScore={}",
                docId, filename, chunks.size(), parse.cleanScore);

        return new IngestionResult(docId, filename, "INGESTED", chunks.size(),
                parse.cleanScore, parse.flags);
    }

    /**
     * 重新处理：删除该文档旧向量后，基于已解析全文（parsed_text）重新分块 + 向量化。
     * 用于分块策略调整后无需重新上传原文件的场景。
     *
     * @return 新的分块数
     */
    public int reprocess(String docId) {
        KnowledgeDoc doc = knowledgeDocRepository.findByDocId(docId);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        String text = doc.parsedText() == null ? "" : doc.parsedText();
        if (text.isBlank()) {
            throw new IllegalStateException("文档缺少解析全文，无法重新处理（请删除后重新上传原文件）: " + docId);
        }
        // 1. 删旧向量
        vectorStore.delete(new FilterExpressionBuilder().eq("doc_id", docId).build());
        // 2. 用解析全文构造单 block，重走柔性分块
        List<com.ecomagent.rag.dto.ParseBlock> blocks = List.of(
                new com.ecomagent.rag.dto.ParseBlock("text", text, 1, 0, text.length() / 2));
        List<Chunk> chunks = chunker.chunk(docId, doc.source(), blocks);
        int indexed = indexChunks(chunks, doc.kbId() == null ? "default" : doc.kbId());
        // 3. 更新元信息
        jdbcTemplateUpdateChunkCount(docId, chunks.size());
        log.info("文档重新处理完成: docId={} 新chunks={}", docId, chunks.size());
        return chunks.size();
    }

    /** 构建 Documents → 向量入库 → 同步 BM25 索引。返回入库 chunk 数。 */
    private int indexChunks(List<Chunk> chunks, String kbId) {
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
                    "heading_path", c.headingPath(),
                    "kb_id", kbId == null ? "default" : kbId);
            documents.add(new Document(c.content(), metadata));
        }
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
            // M5：同步写入过程内 BM25 索引，供混合召回关键词命中
            for (Document d : documents) {
                bm25Index.index(d);
            }
        }
        return documents.size();
    }

    private void jdbcTemplateUpdateChunkCount(String docId, int chunkCount) {
        jdbcTemplate.update(
                "UPDATE knowledge_doc SET chunk_count = ?, status = ? WHERE doc_id = ?",
                chunkCount, KnowledgeDoc.STATUS_INGESTED, docId);
    }

    /**
     * 去重（覆盖语义）：同 source（文件名）的旧文档，先删旧向量 + 旧元信息，再入库。
     *
     * <p>根因：{@code docId = UUID.randomUUID()} 每次上传都新生成，若无此步，
     * 重复上传同一文件会在向量库堆积大量内容相同的 chunk（已实测堆了 7 份）。
     */
    private void dedupeBySource(String source) {
        if (source == null || source.isBlank()) {
            return;
        }
        List<String> oldDocIds = knowledgeDocRepository.findDocIdsBySource(source);
        for (String oldDocId : oldDocIds) {
            try {
                vectorStore.delete(new FilterExpressionBuilder().eq("doc_id", oldDocId).build());
            } catch (Exception e) {
                // 向量删除失败不阻断上传（可能是向量已不存在），元信息仍删，保证下次不再重复
                log.warn("去重删除向量失败: docId={} err={}", oldDocId, e.getMessage());
            }
            knowledgeDocRepository.deleteByDocId(oldDocId);
        }
        if (!oldDocIds.isEmpty()) {
            log.info("去重删除 {} 份旧文档（source={}）", oldDocIds.size(), source);
        }
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
