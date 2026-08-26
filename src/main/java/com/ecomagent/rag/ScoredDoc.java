package com.ecomagent.rag;

import org.springframework.ai.document.Document;

/**
 * 带分数的候选文档（重排/精排内部传递用）。
 */
public record ScoredDoc(Document doc, double score) {
}
