package com.ecomagent.agent;

/**
 * 状态机决策（§8.6）：是否需主动澄清。
 */
public record Decision(boolean needsClarification, String reason) {

    public static Decision proceed() {
        return new Decision(false, null);
    }

    public static Decision clarify(String reason) {
        return new Decision(true, reason);
    }
}
