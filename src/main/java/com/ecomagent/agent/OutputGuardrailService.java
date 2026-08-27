package com.ecomagent.agent;

import com.ecomagent.common.PiiMaskUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 输出护栏（§1.1 + §5，响应方向）。
 *
 * <p>三件事：
 * <ol>
 *   <li><b>PII 输出脱敏</b>：对 LLM 回答遮盖后再推前端（§5 输出缝）；</li>
 *   <li><b>越界拒答</b>：强断言（绝对/肯定/100%…）且无引用 → 判越界；</li>
 *   <li><b>事实一致性</b>：qwen-turbo LLM-as-judge，回答与检索上下文比对，FAIL → 降级话术转人工。</li>
 * </ol>
 */
@Service
public class OutputGuardrailService {

    private static final Logger log = LoggerFactory.getLogger(OutputGuardrailService.class);
    private static final Set<String> STRONG_ASSERTION = Set.of("绝对", "肯定", "100%", "一定", "必然", "我确定", "保证");
    private static final String FALLBACK = "抱歉，这个问题我暂时无法给出可靠答案，已为您转接人工客服。";

    private final ChatModel qwenTurbo;

    public OutputGuardrailService(@Qualifier("qwenTurboChatModel") ChatModel qwenTurbo) {
        this.qwenTurbo = qwenTurbo;
    }

    /** PII 输出脱敏（§5 输出缝） */
    public String maskOutput(String text) {
        return PiiMaskUtil.mask(text);
    }

    /** 越界拒答（§1.1）：含强断言词且无引用标 → 疑似越界编造。 */
    public boolean isOutOfScope(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        boolean strong = STRONG_ASSERTION.stream().anyMatch(answer::contains);
        boolean hasCitation = answer.matches(".*\\[\\d+].*");
        return strong && !hasCitation;
    }

    /** 事实一致性（§1.1 LLM-as-judge）：FAIL → 降级话术。 */
    public GuardrailResult judge(String answer, String context) {
        if (answer == null || answer.isBlank()) {
            return new GuardrailResult("PASS", null);
        }
        try {
            BeanOutputConverter<JudgeVerdict> converter = new BeanOutputConverter<>(JudgeVerdict.class);
            String system = """
                    你是事实一致性裁判。判断「回答」是否忠实于「知识库片段」，不胡编不夸大。
                    只输出 JSON，格式：%s
                    verdict 取 PASS 或 FAIL；回答与知识库不符或凭空捏造时 verdict=FAIL。
                    """.formatted(converter.getFormat());
            String user = "知识库片段：\n" + context + "\n\n回答：\n" + answer;

            ChatResponse resp = qwenTurbo.call(new Prompt(
                    List.of(new SystemMessage(system), new UserMessage(user))));
            JudgeVerdict v = converter.convert(resp.getResult().getOutput().getText());
            String verdict = "FAIL".equalsIgnoreCase(v.verdict()) ? "FAIL" : "PASS";
            return new GuardrailResult(verdict, v.reason());
        } catch (Exception e) {
            // 裁判不可用 → 放行，不阻断主流程
            log.warn("guardrail judge skipped: {}", e.getMessage());
            return new GuardrailResult("PASS", null);
        }
    }

    public String fallbackMessage() {
        return FALLBACK;
    }

    public record JudgeVerdict(@JsonProperty("verdict") String verdict, @JsonProperty("reason") String reason) {
    }

    public record GuardrailResult(String verdict, String reason) {
        public boolean failed() {
            return "FAIL".equals(verdict);
        }
    }
}
