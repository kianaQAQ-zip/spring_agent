package com.ecomagent.api;

import com.ecomagent.rag.DocChunk;
import com.ecomagent.rag.IngestionResult;
import com.ecomagent.rag.KbIngestionService;
import com.ecomagent.rag.KnowledgeDoc;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KbController Web 层测试：验证 /kb/upload（multipart）与 /kb/doc/{docId} 端点契约与信封。
 */
@WebMvcTest(KbController.class)
class KbControllerTest {

    @MockBean
    private KbIngestionService ingestionService;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadReturnsEnvelope() throws Exception {
        IngestionResult result = new IngestionResult("doc-1", "policy.txt", "INGESTED", 3, 0.95, List.of());
        when(ingestionService.ingest(any())).thenReturn(result);

        MockMultipartFile file = new MockMultipartFile("file", "policy.txt", "text/plain", "内容".getBytes());
        mockMvc.perform(multipart("/kb/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("INGESTED"))
                .andExpect(jsonPath("$.data.docId").value("doc-1"));
    }

    @Test
    void getDocReturnsEnvelope() throws Exception {
        KnowledgeDoc doc = new KnowledgeDoc("id-1", "doc-1", "default", "policy.txt", 3, "全文");
        when(ingestionService.getDoc(eq("doc-1"))).thenReturn(doc);

        mockMvc.perform(get("/kb/doc/doc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.parsedText").value("全文"));
    }

    @Test
    void getDocNotFoundReturnsFailCode() throws Exception {
        when(ingestionService.getDoc(eq("missing"))).thenReturn(null);

        mockMvc.perform(get("/kb/doc/missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void getDocChunkReturnsHighlight() throws Exception {
        DocChunk chunk = new DocChunk("doc-1", "policy.txt", "全文", 1, "七天无理由");
        when(ingestionService.getChunk(eq("doc-1"), eq(1))).thenReturn(chunk);

        mockMvc.perform(get("/kb/doc/doc-1").param("chunk", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.chunkContent").value("七天无理由"))
                .andExpect(jsonPath("$.data.chunkIndex").value(1));
    }
}
