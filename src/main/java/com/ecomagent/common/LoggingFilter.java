package com.ecomagent.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全局 HTTP 请求日志（入口 / 出口 / 异常三段式）。
 *
 * <p>每个经过的请求都打印：
 * <pre>
 * --> [GET] /chat/stream conversationId=xx&message=...(脱敏截断)
 * <-- [GET] /chat/stream 耗时 2345ms 状态 200
 * !!! [GET] /kb/upload 耗时 12ms 异常: ...（含完整堆栈）
 * </pre>
 *
 * <p>统一使用 {@code API-TRACE} logger 名，可在 application.yml 单独调级别。
 * query 参数经 {@link PiiMaskUtil#mask} 脱敏并截断——用户消息会进 query（SSE GET 请求），不能原文进日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("API-TRACE");
    private static final int MAX_LEN = 300;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 健康探测类噪音不打业务日志
        String path = request.getRequestURI();
        return path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String qs = request.getQueryString();
        String params = qs == null ? "" : PiiMaskUtil.mask(truncate(qs));

        log.info("--> [{}] {} {}", method, uri, params);
        try {
            chain.doFilter(request, response);
            log.info("<-- [{}] {} {}ms 状态 {}", method, uri,
                    System.currentTimeMillis() - start, response.getStatus());
        } catch (Exception e) {
            log.error("!!! [{}] {} {}ms 异常: {}", method, uri,
                    System.currentTimeMillis() - start, e.getMessage(), e);
            throw e;
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_LEN ? s : s.substring(0, MAX_LEN) + "...(截断)";
    }
}
