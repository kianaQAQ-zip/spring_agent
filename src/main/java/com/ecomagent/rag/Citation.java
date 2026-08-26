package com.ecomagent.rag;

/**
 * 引用条目（§5 溯源）：回答中的 {@code [n]} 对应此列表，随 SSE {@code event: citations} 先发。
 */
public record Citation(
        int index,
        String docId,
        String source,
        String chunkContent,
        Integer page,
        double score) {
}
