package com.ecomagent.common;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 降级标记（可观测性）。
 *
 * <p>LLM 链路各处的 try-catch 会把失败吞掉、换成兜底值——系统表面上"照常工作"，
 * 实际已从 Agent 退化为检索器：intent/orderId 全空、工具永不触发、检索用未改写的口语。
 * 这类<b>静默失效</b>比直接 500 更危险，因为没人知道它坏了。
 *
 * <p>本组件把每个被吞掉的失败显式记下来，供 {@code /chat/health} 与前端 citations 事件读取。
 * 标记带 TTL，模型恢复后自动消失，无需手工清理。
 */
@Component
public class DegradationFlags {

    /** L2 状态提取失败 → intent/orderId 拿不到，四个工具永不触发 */
    public static final String STATE_EXTRACT = "state-extract";
    /** QueryRewrite 失败 → 用原句检索，RAG 命中率下降 */
    public static final String QUERY_REWRITE = "query-rewrite";
    /** 事实一致性裁判不可用 → 放行，输出护栏失效 */
    public static final String GUARDRAIL = "guardrail";
    /** 主对话模型不可用 */
    public static final String CHAT = "chat";
    /** Embedding 不可用 → 知识库无法入库，检索退化 */
    public static final String EMBEDDING = "embedding";
    /** 会话落库失败 → 历史会话/统计/导出会丢数据（静默失效的高危项） */
    public static final String PERSISTENCE = "persistence";
    /** 写操作执行失败（退款/改地址/发券）→ 回退 mock 结果，坐席看到的 EXECUTED 是假的 */
    public static final String ACTION_EXEC = "action-exec";

    private static final Duration TTL = Duration.ofMinutes(5);

    private final Map<String, Instant> flags = new ConcurrentHashMap<>();

    /** 记录一次降级（在被吞掉的 catch 块里调用）。 */
    public void mark(String capability) {
        flags.put(capability, Instant.now());
    }

    /** 调用成功，撤销降级标记——不必等 TTL 到期。 */
    public void clear(String capability) {
        flags.remove(capability);
    }

    /** 当前仍处于降级期的能力列表（字典序，输出稳定）。惰性清理过期项。 */
    public List<String> degraded() {
        Instant cutoff = Instant.now().minus(TTL);
        List<String> active = new ArrayList<>();
        for (Map.Entry<String, Instant> e : flags.entrySet()) {
            if (e.getValue().isAfter(cutoff)) {
                active.add(e.getKey());
            } else {
                flags.remove(e.getKey());
            }
        }
        active.sort(String::compareTo);
        return List.copyOf(active);
    }

    public boolean isDegraded() {
        return !degraded().isEmpty();
    }
}
