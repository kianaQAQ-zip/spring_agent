package com.ecomagent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 评估执行器（§9 四维判分）：关键词覆盖 / 意图正确 / 引用接地 / 忠实度。
 *
 * <p>纯判分逻辑，不依赖真实 LLM；「Agent」接口由调用方注入（生产接真实回答，测试用 mock）。
 * 输出 JSON / HTML 报告，可作 CI 门禁。
 */
@Component
public class EvalRunner {

    public static final double PASS_THRESHOLD = 0.6;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Agent 回答接口：query → 意图/文本/是否带引用/是否忠实。 */
    public interface Agent {
        AgentAnswer answer(String query);
    }

    public EvalReport run(List<EvalCase> cases, Agent agent) {
        List<CaseScore> scores = new ArrayList<>(cases.size());
        for (EvalCase c : cases) {
            scores.add(score(c, agent.answer(c.query())));
        }
        double agg = scores.stream().mapToDouble(CaseScore::total).average().orElse(0);
        int passed = (int) scores.stream().filter(CaseScore::pass).count();
        return new EvalReport(scores, agg, passed, cases.size());
    }

    public CaseScore score(EvalCase c, AgentAnswer a) {
        double keyword = keywordCoverage(c.keywords(), a.text());
        double intent = intentMatch(c.expectedIntent(), a.intent());
        double grounding = a.cited() ? 1.0 : 0.0;
        double faithfulness = a.faithful() ? 1.0 : 0.0;
        double total = (keyword + intent + grounding + faithfulness) / 4.0;
        return new CaseScore(c.id(), c.category(), keyword, intent, grounding, faithfulness,
                total, total >= PASS_THRESHOLD);
    }

    private double keywordCoverage(List<String> keywords, String text) {
        if (keywords == null || keywords.isEmpty()) {
            return 1.0;
        }
        String t = text == null ? "" : text;
        long hit = keywords.stream().filter(t::contains).count();
        return (double) hit / keywords.size();
    }

    private double intentMatch(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return 1.0;
        }
        return expected.equals(actual) ? 1.0 : 0.0;
    }

    /** 从 classpath 加载默认评估集（eval/eval-set.json）。 */
    public List<EvalCase> loadDefaultCases() {
        try (InputStream in = getClass().getResourceAsStream("/eval/eval-set.json")) {
            if (in == null) {
                return List.of();
            }
            return MAPPER.readValue(in, new TypeReference<List<EvalCase>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("加载 eval-set.json 失败", e);
        }
    }
}
