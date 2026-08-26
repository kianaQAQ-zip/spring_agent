package com.ecomagent.rag;

import com.ecomagent.rag.dto.ParseBlock;
import com.ecomagent.rag.dto.ParseResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * doc-processor（Python，M1.5）REST 客户端（§10）。
 *
 * <p>封装对 {@code /api/v1/parse} 的调用：multipart 上传文件，超时 30s（OCR/rerank 可能慢）。
 * 任何网络异常 / 不可达 / 业务 ok=false 均返回 {@link ParseResult#unreachable()}，
 * 由上层 {@code KbIngestionService} 转 Tika 兜底，<b>不阻断主流程</b>（§10.5 故障隔离）。
 */
@Component
public class DocProcessorClient {

    private final RestClient restClient;

    public DocProcessorClient(RestClient.Builder builder,
                              @Value("${doc-processor.url:http://localhost:8000}") String baseUrl) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(rf).build();
    }

    public ParseResult parse(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            };
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", resource);
            body.add("options", "{\"ocr\":true,\"clean\":true}");

            DocProcessorParseResponse resp = restClient.post()
                    .uri("/api/v1/parse")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(DocProcessorParseResponse.class);

            if (resp == null || !resp.ok() || resp.data() == null) {
                return ParseResult.unreachable();
            }
            List<ParseBlock> blocks = resp.data().blocks().stream().map(b ->
                    new ParseBlock(b.blockType(), b.text(), b.page(),
                            b.readingOrder(), b.tokenCount())).collect(Collectors.toList());
            String parsedText = blocks.stream().map(ParseBlock::text)
                    .collect(Collectors.joining("\n\n"));
            return ParseResult.fromDocProcessor(blocks, resp.data().cleanScore(),
                    resp.data().flags(), parsedText);
        } catch (RestClientException | IOException e) {
            return ParseResult.unreachable();
        }
    }

    /**
     * Cross-encoder 重排（§9.5 Stage2，经 doc-processor 的 bge-reranker-v2-m3）。
     * 不可达时返回空列表（上层降级为 score 阈值 + MMR）。
     */
    public List<RerankHit> rerank(String query, List<String> docIds, List<String> texts, int topN) {
        try {
            List<Map<String, String>> documents = new ArrayList<>(docIds.size());
            for (int i = 0; i < docIds.size(); i++) {
                documents.add(Map.of("id", docIds.get(i), "text", texts.get(i)));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", topN);

            RerankResponse resp = restClient.post()
                    .uri("/api/v1/rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(RerankResponse.class);

            if (resp == null || !resp.ok() || resp.data() == null) {
                return List.of();
            }
            return resp.data().ranked();
        } catch (RestClientException e) {
            return List.of();
        }
    }

    // ---- doc-processor /api/v1/parse 响应契约（§10.2） ----
    public record DocProcessorParseResponse(boolean ok, ParseData data, String error) {
    }

    // ---- doc-processor /api/v1/rerank 响应契约（§10.2） ----
    public record RerankHit(String id, double score) {
    }

    public record RerankData(List<RerankHit> ranked) {
    }

    public record RerankResponse(boolean ok, RerankData data, String error) {
    }

    public record ParseData(
            List<ParseBlockDto> blocks,
            @JsonProperty("clean_score") double cleanScore,
            List<String> flags) {
    }

    public record ParseBlockDto(
            @JsonProperty("block_type") String blockType,
            String text,
            List<Double> bbox,
            Integer page,
            @JsonProperty("reading_order") Integer readingOrder,
            @JsonProperty("token_count") Integer tokenCount) {
    }
}
