package com.ecomagent.rag;

/**
 * 单个知识块（§9.4 柔性分块产物）。
 * 入库时由 {@code KbIngestionService} 补充 tenant 等 metadata 并转为 Spring AI {@code Document}。
 */
public record Chunk(
        String docId,
        int chunkIndex,
        String source,
        String content,
        String blockType,
        boolean atomic,
        int page,
        int tokenCount,
        String headingPath) {
}
