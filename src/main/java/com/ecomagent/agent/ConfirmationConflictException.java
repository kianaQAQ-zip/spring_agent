package com.ecomagent.agent;

/**
 * 确认冲突异常（§2.3 双执行防护）。
 *
 * <p>当确认操作发现该 pending 已不再是 pending（已被确认/驳回/过期）时抛出，
 * 表示并发或重复确认被行级 UPDATE 拦截，受影响行数为 0。
 */
public class ConfirmationConflictException extends RuntimeException {

    public ConfirmationConflictException(String message) {
        super(message);
    }
}
