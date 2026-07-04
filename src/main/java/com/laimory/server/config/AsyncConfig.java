package com.laimory.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** @Async 활성화. 실행기는 Boot 기본 applicationTaskExecutor(ThreadPoolTaskExecutor)를 그대로 쓴다. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
