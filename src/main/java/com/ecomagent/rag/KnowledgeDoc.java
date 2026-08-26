package com.ecomagent.rag;

/**
 * 知识文档元信息（对应 {@code knowledge_doc} 表，§7 / M2）。
 * {@code parsedText} 存解析全文，供 M5 点击引用溯源时展示原文。
 */
public record KnowledgeDoc(
        String id,
        String docId,
        String tenantId,
        String source,
        int chunkCount,
        String parsedText) {
}
