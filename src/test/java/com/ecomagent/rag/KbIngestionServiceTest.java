package com.ecomagent.rag;

import com.ecomagent.rag.dto.ParseBlock;
import com.ecomagent.rag.dto.ParseResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KbIngestionService 编排集成测试（M2）。
 *
 * <p>不联网验证：doc-processor / Tika / VectorStore 全部 mock，knowledge_doc 用真实 H2 落库。
 * 覆盖：doc-processor 不可达 → Tika 兜底 → 分块 → 向量入库（mock 校验）+ 文档落库；
 * 以及 clean_score 过低 → 隔离（QUARANTINED，不入库）。
 */
@SpringBootTest
class KbIngestionServiceTest {

    @MockBean
    private DocProcessorClient docProcessorClient;
    @MockBean
    private TikaDocumentLoader tikaDocumentLoader;
    @MockBean
    private VectorStore vectorStore;

    @Autowired
    private KbIngestionService ingestionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS knowledge_doc ("
                + "id VARCHAR(36) PRIMARY KEY, doc_id VARCHAR(64), tenant_id VARCHAR(64), "
                + "source VARCHAR(512), chunk_count INT, parsed_text TEXT, created_at TIMESTAMP, "
                + "kb_id VARCHAR(64), file_size BIGINT, status VARCHAR(16), clean_score DOUBLE)");
        jdbcTemplate.execute("DELETE FROM knowledge_doc");
        when(docProcessorClient.parse(any())).thenReturn(ParseResult.unreachable());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM knowledge_doc");
    }

    private List<ParseBlock> sampleBlocks() {
        return List.of(
                new ParseBlock("text", "七天无理由退货政策内容描述文字说明。", 1, 0, 12),
                new ParseBlock("text", "质量问题支持换货相关说明文字内容描述。", 1, 0, 12));
    }

    @Test
    void ingestFallsBackToTikaAndStores() {
        when(tikaDocumentLoader.parse(any())).thenReturn(
                ParseResult.fallback("tika_fallback", sampleBlocks(), "七天无理由退货政策内容描述文字说明。质量问题支持换货相关说明文字内容描述。"));

        MultipartFile file = new MockMultipartFile("file", "policy.txt", "text/plain", "原始内容".getBytes());
        IngestionResult result = ingestionService.ingest(file);

        assertEquals("INGESTED", result.status());
        assertTrue(result.chunkCount() > 0);
        verify(vectorStore, times(1)).add(anyList());
        KnowledgeDoc doc = ingestionService.getDoc(result.docId());
        assertNotNull(doc);
        assertEquals(result.chunkCount(), doc.chunkCount());
        assertEquals("policy.txt", doc.source());
    }

    @Test
    void lowCleanScoreIsQuarantined() {
        // 构造低质量解析结果（cleanScore 低于阈值 → 隔离）
        ParseResult dirty = ParseResult.fallbackWithScore("tika_fallback", sampleBlocks(), 0.1,
                List.of("low_quality"), "噪声文本");
        when(tikaDocumentLoader.parse(any())).thenReturn(dirty);

        MultipartFile file = new MockMultipartFile("file", "policy.txt", "text/plain", "原始内容".getBytes());
        IngestionResult result = ingestionService.ingest(file);

        assertEquals("QUARANTINED", result.status());
        verify(vectorStore, times(0)).add(anyList());
        // 不入向量库，但元信息落库（status=QUARANTINED），供失败列表排查
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_doc WHERE status = 'QUARANTINED'", Integer.class));
    }
}
