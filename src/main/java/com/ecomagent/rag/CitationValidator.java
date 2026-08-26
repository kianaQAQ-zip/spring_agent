package com.ecomagent.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用标注校验（§5 后处理，防模型编造引用）。
 *
 * <p>从模型回答中抽取 {@code [n]} 引用标，校验 n 是否越界（超出重排后的文档数量）。
 */
public final class CitationValidator {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    private CitationValidator() {
    }

    /** 抽取回答中出现的所有引用编号（去重、升序）。 */
    public static Set<Integer> extractIndices(String text) {
        Set<Integer> indices = new LinkedHashSet<>();
        if (text == null) {
            return indices;
        }
        Matcher m = CITATION_PATTERN.matcher(text);
        while (m.find()) {
            indices.add(Integer.parseInt(m.group(1)));
        }
        return indices;
    }

    /** 越界引用编号（n < 1 或 n > maxIndex）。 */
    public static List<Integer> outOfRange(String text, int maxIndex) {
        List<Integer> out = new ArrayList<>();
        for (Integer idx : extractIndices(text)) {
            if (idx < 1 || idx > maxIndex) {
                out.add(idx);
            }
        }
        return out;
    }

    /** 回答是否所有引用均不越界。 */
    public static boolean isValid(String text, int maxIndex) {
        return outOfRange(text, maxIndex).isEmpty();
    }
}
