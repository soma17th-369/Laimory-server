package com.laimory.server.config;

import com.laimory.server.user.service.AccountErasureWorkerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 계정 삭제 worker의 bounded executor(#302).
 *
 * <p><b>두 pass가 executor를 공유하지 않는 이유</b>: 이 저장소의 worker executor는 pool = concurrency,
 * {@code queueCapacity(0)}이다. 공유하면 일일 삭제 run이 pool을 점유하는 동안 짧은 주기의 정지 trigger가
 * {@code RejectedExecutionException}으로 거절되고 그 회차가 통째로 건너뛰어진다 — 정지가 최대
 * {@code max-run-duration}만큼 밀린다.
 */
@Configuration
public class AccountErasureWorkerExecutorConfig {

    @Bean(name = "accountErasureQuiesceWorkerExecutor", defaultCandidate = false)
    public ThreadPoolTaskExecutor accountErasureQuiesceWorkerExecutor(
            AccountErasureWorkerProperties properties) {
        return executor(properties.getConcurrency(), "erasure-quiesce-");
    }

    @Bean(name = "accountErasureDeleteWorkerExecutor", defaultCandidate = false)
    public ThreadPoolTaskExecutor accountErasureDeleteWorkerExecutor(
            AccountErasureWorkerProperties properties) {
        return executor(properties.getConcurrency(), "erasure-delete-");
    }

    private static ThreadPoolTaskExecutor executor(int concurrency, String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix(threadNamePrefix);
        return executor;
    }
}
