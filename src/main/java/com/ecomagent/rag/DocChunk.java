package com.ecomagent.rag;

/**
 * 文档溯源响应（M5）：全文 + 定位到的 chunk（供前端源抽屉高亮）。
 */
public record DocChunk(
        String docId,
        String source,
        String parsedText,
        int chunkIndex,
        String chunkContent) {
}
