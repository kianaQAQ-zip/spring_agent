package com.ecomagent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引用标注校验单测（§5 后处理，防模型编造引用）。
 */
class CitationValidatorTest {

    @Test
    void extractsIndices() {
        Set<Integer> indices = CitationValidator.extractIndices("参见 [1] 与 [3]，另见[2]。");
        assertTrue(indices.containsAll(Set.of(1, 2, 3)));
    }

    @Test
    void detectsOutOfRange() {
        List<Integer> out = CitationValidator.outOfRange("参见 [1][5]", 3);
        assertTrue(out.contains(5), "越界引用应被识别");
        assertFalse(out.contains(1), "合法引用不应被误判");
    }

    @Test
    void isValidWhenInRange() {
        assertTrue(CitationValidator.isValid("参见 [1][2]", 2));
        assertFalse(CitationValidator.isValid("参见 [3]", 2));
    }

    @Test
    void emptyTextIsValid() {
        assertTrue(CitationValidator.isValid(null, 0));
        assertEquals(0, CitationValidator.extractIndices(null).size());
    }
}
