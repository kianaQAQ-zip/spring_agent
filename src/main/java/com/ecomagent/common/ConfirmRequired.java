package com.ecomagent.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 确认护栏标记（§2，HITL）。
 *
 * <p>标注在 {@code @Tool} 方法上，表示该动作（退款/改地址/发券）需人工确认后才真正执行。
 * 只读工具（如订单查询）不标注，直接执行。被标注的方法在真正执行副作用前，
 * 必须经 {@code ConfirmationService.request(...)} 写入 {@code pending_action} 并返回 PENDING，
 * 由坐席侧确认（{@code ConfirmController}）后原子翻转为 confirmed 才落地执行。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfirmRequired {

    /** 工具显示名（坐席确认台渲染用） */
    String label() default "";
}
