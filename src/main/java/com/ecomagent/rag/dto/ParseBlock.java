package com.ecomagent.rag.dto;

/**
 * 解析区块（对齐 doc-processor /api/v1/parse 返回的单个 block）。
 */
public record ParseBlock(
        String blockType,
        String text,
        Integer page,
        Integer readingOrder,
        Integer tokenCount) {
}
