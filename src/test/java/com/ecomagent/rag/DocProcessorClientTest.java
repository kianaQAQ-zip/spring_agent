package com.ecomagent.rag;

import com.ecomagent.rag.dto.ParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * DocProcessorClient 降级契约测试：doc-processor 不可达（抛 RestClientException）时，
 * parse() 必须返回 reachable=false，交由上层 Tika 兜底（§10.5 故障隔离）。
 */
@ExtendWith(MockitoExtension.class)
class DocProcessorClientTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private RestClient.Builder builder;

    @Test
    void unreachableReturnsFallbackMarker() {
        // deep-stub 的 RestClient 链对 .body(Class) 返回 null（模拟 doc-processor 无响应），
        // parse() 判空返回 unreachable，交由上层 Tika 兜底。
        DocProcessorClient client = new DocProcessorClient(builder, "http://localhost:9/not-there");

        MockMultipartFile file = new MockMultipartFile(
                "file", "policy.pdf", "application/pdf", "退货政策内容".getBytes());
        ParseResult result = client.parse(file);

        assertFalse(result.reachable, "doc-processor 不可达时应标记 unreachable");
    }
}
