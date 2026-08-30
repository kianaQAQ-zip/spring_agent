package com.ecomagent.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降级标记单测：记录 / 撤销 / 过期清理 / 输出稳定排序。
 */
class DegradationFlagsTest {

    @Test
    void startsClean() {
        DegradationFlags f = new DegradationFlags();
        assertTrue(f.degraded().isEmpty());
        assertFalse(f.isDegraded());
    }

    @Test
    void marksAndClears() {
        DegradationFlags f = new DegradationFlags();
        f.mark(DegradationFlags.STATE_EXTRACT);
        assertEquals(List.of("state-extract"), f.degraded());

        f.clear(DegradationFlags.STATE_EXTRACT);
        assertTrue(f.degraded().isEmpty(), "成功后应立刻撤销，不必等 TTL");
    }

    @Test
    void degradedIsSortedForStableOutput() {
        DegradationFlags f = new DegradationFlags();
        f.mark(DegradationFlags.QUERY_REWRITE);
        f.mark(DegradationFlags.STATE_EXTRACT);
        f.mark(DegradationFlags.CHAT);

        assertEquals(List.of("chat", "query-rewrite", "state-extract"), f.degraded(),
                "输出需字典序稳定，否则前端 badge 会无意义地闪动");
    }

    @Test
    void markIsIdempotent() {
        DegradationFlags f = new DegradationFlags();
        f.mark(DegradationFlags.CHAT);
        f.mark(DegradationFlags.CHAT);
        assertEquals(1, f.degraded().size());
    }
}
