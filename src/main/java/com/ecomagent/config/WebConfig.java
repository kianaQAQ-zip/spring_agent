package com.ecomagent.config;

import com.ecomagent.common.ContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置（§9 多租户 + 多渠道接缝）：注册 {@link ContextInterceptor} 到所有路径。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ContextInterceptor contextInterceptor;

    public WebConfig(ContextInterceptor contextInterceptor) {
        this.contextInterceptor = contextInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(contextInterceptor).addPathPatterns("/**");
    }
}
