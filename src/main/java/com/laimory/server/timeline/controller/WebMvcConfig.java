package com.laimory.server.timeline.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 내부 콜백 secret 인터셉터를 {@code /internal/**} 경로에 등록한다. */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CallbackSecretInterceptor callbackSecretInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(callbackSecretInterceptor).addPathPatterns("/internal/**");
    }
}
