package com.ecomagent.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 检索结果：按重排后顺序排列的文档 + 对应引用条目。
 */
public record RetrievalResult(List<Document> documents, List<Citation> citations) {
}
