package com.laimory.server.config;

import com.laimory.server.user.service.AccountErasureWorkerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 계정 삭제 worker의 bounded executor(#302). pool = concurrency, {@code queueCapacity(0)}. */
@Configuration
public class AccountErasureWorkerExecutorConfig {

    @Bean(name = "accountErasureDeleteWorkerExecutor", defaultCandidate = false)
    public ThreadPoolTaskExecutor accountErasureDeleteWorkerExecutor(
            AccountErasureWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getConcurrency());
        executor.setMaxPoolSize(properties.getConcurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("erasure-delete-");
        return executor;
    }
}
