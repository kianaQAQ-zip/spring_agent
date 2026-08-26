package com.ecomagent.agent;

/**
 * 客服意图枚举（§8.6 状态机）。
 */
public enum Intent {
    ORDER_QUERY,
    REFUND,
    ADDRESS_CHANGE,
    COUPON,
    KNOWLEDGE_QA,
    CHITCHAT,
    UNKNOWN
}
