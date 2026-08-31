package com.ecomagent.order;

import java.time.Instant;

/** 物流轨迹节点。 */
public record TraceNode(int seq, String node, Instant happenedAt) {
}
