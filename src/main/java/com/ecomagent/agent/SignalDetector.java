package com.ecomagent.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则信号检测（§8.8.1）：纯规则打分，无 LLM 调用。
 *
 * <p>三大要点：
 * <ul>
 *   <li><b>否定窗口</b>：关键词前后 3 字符内出现否定词（没/不/无/别…）即视为否定，
 *       如「心情不好但订单没问题」不误触发订单意图；</li>
 *   <li><b>订单号抽取</b>：正则 {@code ORD-\d+}，多订单交由状态机澄清；</li>
 *   <li><b>孤立情绪不触发</b>：纯情绪表达（无实质内容）不标记情绪，避免无谓 LLM 调用。</li>
 * </ul>
 */
@Component
public class SignalDetector {

    private static final Pattern ORDER_ID = Pattern.compile("ORD-\\d+");
    private static final Set<String> NEGATION = Set.of("没", "不", "无", "别", "未", "非");
    private static final Set<String> PRONOUN = Set.of("这个", "那个", "它", "这单", "那单");
    private static final List<String> EMOTION_WORDS =
            List.of("生气", "火大", "失望", "委屈", "着急", "气死", "烦死");

    public Signal detect(String text) {
        if (text == null || text.isBlank()) {
            return Signal.empty();
        }
        List<String> orderIds = extractOrderIds(text);
        return new Signal(
                detectIntent(text),
                orderIds.isEmpty() ? null : orderIds.get(0),
                detectEmotion(text),
                orderIds.size(),
                hasPronoun(text));
    }

    private Intent detectIntent(String text) {
        if (containsGuarded(text, "退")) {
            return Intent.REFUND;
        }
        if (containsGuarded(text, "地址") || containsGuarded(text, "改收货")) {
            return Intent.ADDRESS_CHANGE;
        }
        if (containsGuarded(text, "券") || containsGuarded(text, "优惠")) {
            return Intent.COUPON;
        }
        if (containsGuarded(text, "物流") || containsGuarded(text, "发货")
                || containsGuarded(text, "到哪") || containsGuarded(text, "订单")) {
            return Intent.ORDER_QUERY;
        }
        if (contains(text, "政策") || contains(text, "规则")
                || contains(text, "七天") || contains(text, "无理由") || contains(text, "怎么算")) {
            return Intent.KNOWLEDGE_QA;
        }
        return Intent.UNKNOWN;
    }

    /** 关键词命中且否定窗口内无否定词才为真。 */
    private boolean containsGuarded(String text, String keyword) {
        int idx = text.indexOf(keyword);
        while (idx >= 0) {
            if (!hasNegationNear(text, idx)) {
                return true;
            }
            idx = text.indexOf(keyword, idx + 1);
        }
        return false;
    }

    private boolean contains(String text, String keyword) {
        return text.contains(keyword);
    }

    private boolean hasNegationNear(String text, int keywordIdx) {
        int start = Math.max(0, keywordIdx - 3);
        int end = Math.min(text.length(), keywordIdx + 3);
        String window = text.substring(start, end);
        for (String n : NEGATION) {
            if (window.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private String detectEmotion(String text) {
        for (String e : EMOTION_WORDS) {
            if (text.contains(e)) {
                String rest = text.replace(e, "").trim();
                // 孤立情绪词（无实质内容）不标记，避免触发 LLM 情绪提取
                if (rest.length() >= 2) {
                    return e;
                }
            }
        }
        return null;
    }

    private List<String> extractOrderIds(String text) {
        Matcher m = ORDER_ID.matcher(text);
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            ids.add(m.group());
        }
        return ids;
    }

    private boolean hasPronoun(String text) {
        for (String p : PRONOUN) {
            if (text.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
