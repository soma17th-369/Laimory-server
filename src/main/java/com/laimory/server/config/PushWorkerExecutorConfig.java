package com.laimory.server.config;

import com.laimory.server.push.service.DailyReminderWorkerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 매분 도는 스케줄 trigger와 실제 claim·FCM I/O를 분리하는 bounded worker executor.
 * 공용 scheduling pool을 점유하지 않게 전용 pool을 쓰고 queue를 두지 않아 초과 제출이 즉시 드러난다
 * (타임라인 worker executor 선례).
 */
@Configuration
public class PushWorkerExecutorConfig {

    @Bean(name = "dailyReminderWorkerExecutor", defaultCandidate = false)
    public ThreadPoolTaskExecutor dailyReminderWorkerExecutor(DailyReminderWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getConcurrency());
        executor.setMaxPoolSize(properties.getConcurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("daily-reminder-");
        return executor;
    }
}
