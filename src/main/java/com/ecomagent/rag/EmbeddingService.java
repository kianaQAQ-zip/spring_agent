package com.ecomagent.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 封装（§9 / M2 任务5）。
 *
 * <p>统一走通义 text-embedding-v3（1024 维），提供：
 * <ul>
 *   <li>批量：按 {@code batchSize} 分批，降低单次请求体量；</li>
 *   <li>重试：单批失败指数退避重试，避免偶发 429/超时导致整批入库失败；</li>
 *   <li>速率保护：批间短暂停顿（可调），防止触发百炼 QPS 限制。</li>
 * </ul>
 *
 * <p>注：M2 入库路径由 {@code PgVectorStore.add} 内部调用 Tongyi bean 完成 embedding；
 * 本服务主要供检索侧（M5）对 query 批量编码复用，亦可作为显式批量编码入口。
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final int batchSize;
    private final long interBatchDelayMs;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this(embeddingModel, 16, 50);
    }

    public EmbeddingService(EmbeddingModel embeddingModel, int batchSize, long interBatchDelayMs) {
        this.embeddingModel = embeddingModel;
        this.batchSize = batchSize;
        this.interBatchDelayMs = interBatchDelayMs;
    }

    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + batchSize));
            out.addAll(retryBatch(batch));
            if (interBatchDelayMs > 0 && i + batchSize < texts.size()) {
                sleep(interBatchDelayMs);
            }
        }
        return out;
    }

    private List<float[]> retryBatch(List<String> batch) {
        int attempt = 0;
        while (true) {
            try {
                List<float[]> r = new ArrayList<>(batch.size());
                for (String t : batch) {
                    r.add(embeddingModel.embed(t));
                }
                return r;
            } catch (RuntimeException e) {
                if (++attempt >= 3) {
                    throw e;
                }
                sleep(500L * attempt);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
