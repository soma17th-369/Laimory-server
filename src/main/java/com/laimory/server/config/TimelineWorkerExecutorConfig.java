package com.laimory.server.config;

import com.laimory.server.timeline.service.TimelineDraftCleanupWorkerProperties;
import com.laimory.server.timeline.service.TimelinePhotoDeleteWorkerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 스케줄 trigger와 실제 PHOTO 삭제 I/O를 분리하는 bounded worker executor. */
@Configuration
public class TimelineWorkerExecutorConfig {

    @Bean(name = "timelineDraftCleanupWorkerExecutor", defaultCandidate = false)
    public ThreadPoolTaskExecutor timelineDraftCleanupWorkerExecutor(
            TimelineDraftCleanupWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getConcurrency());
        executor.setMaxPoolSize(properties.getConcurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("draft-cleanup-");
        return executor;
    }

    @Bean(name = "timelinePhotoDeleteWorkerExecutor", defaultCandidate = false)
    public ThreadPoolTaskExecutor timelinePhotoDeleteWorkerExecutor(
            TimelinePhotoDeleteWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getConcurrency());
        executor.setMaxPoolSize(properties.getConcurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("photo-delete-");
        return executor;
    }
}
