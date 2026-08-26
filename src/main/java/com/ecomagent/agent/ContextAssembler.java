package com.ecomagent.agent;

import com.ecomagent.rag.RagDocUtils;
import com.ecomagent.rag.TokenUtils;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文装配（§8.8.2）：分区装配模板 + Token 预算。
 *
 * <p>把「系统指令 / L2 会话状态 / 检索知识（编号 [n]）/ 对话」分区装配；
 * {@code DocumentSelector} 按 token 累加选 RAG 文档（上限 1500），超 6k 时按优先级丢弃
 * （此处体现为仅在装配时做预算截断，优先级由重排/精排已保证）。
 */
@Component
public class ContextAssembler {

    private static final int RAG_TOKEN_BUDGET = 1500;

    public String assemble(SessionState state, List<Document> ragDocs) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是电商客服助手。请严格依据知识库片段回答，并在引用处标注来源编号 [n]。\n\n");

        // 状态分区
        if (state != null && (state.intent() != null || state.orderId() != null || state.emotion() != null)) {
            sb.append("当前会话状态：");
            if (state.intent() != null) {
                sb.append("意图=").append(state.intent()).append("；");
            }
            if (state.orderId() != null && !state.orderId().isBlank()) {
                sb.append("订单号=").append(state.orderId()).append("；");
            }
            if (state.emotion() != null && !state.emotion().isBlank()) {
                sb.append("情绪=").append(state.emotion());
            }
            sb.append("\n\n");
        }

        // 知识分区（token 预算选优）
        List<Document> selected = selectDocuments(ragDocs, RAG_TOKEN_BUDGET);
        for (int i = 0; i < selected.size(); i++) {
            sb.append("[").append(i + 1).append("] ")
                    .append(selected.get(i).getText()).append("\n\n");
        }

        sb.append("若知识库不足以回答，请如实说明，不要编造。");
        return sb.toString();
    }

    /** DocumentSelector：按 token 累加选文档至预算（§8.8.2）。 */
    public List<Document> selectDocuments(List<Document> docs, int budget) {
        List<Document> out = new ArrayList<>();
        int acc = 0;
        for (Document d : docs) {
            int tok = tokenCount(d);
            if (!out.isEmpty() && acc + tok > budget) {
                break;
            }
            out.add(d);
            acc += tok;
        }
        return out;
    }

    private int tokenCount(Document d) {
        String raw = RagDocUtils.str(d.getMetadata().get(RagDocUtils.KEY_TOKEN_COUNT));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return TokenUtils.estimateTokens(d.getText());
        }
    }
}
