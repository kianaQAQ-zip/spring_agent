package com.ecomagent.rag;

import com.ecomagent.rag.dto.ParseResult;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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

    private static final String DEFAULT_TENANT = "default";
    private static final double CLEAN_SCORE_THRESHOLD = 0.5;

    public KbIngestionService(DocProcessorClient docProcessorClient,
                              TikaDocumentLoader tikaDocumentLoader,
                              StructureAwareChunker chunker,
                              VectorStore vectorStore,
                              KnowledgeDocRepository knowledgeDocRepository) {
        this.docProcessorClient = docProcessorClient;
        this.tikaDocumentLoader = tikaDocumentLoader;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.knowledgeDocRepository = knowledgeDocRepository;
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
}
