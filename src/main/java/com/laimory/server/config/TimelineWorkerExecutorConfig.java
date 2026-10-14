package com.laimory.server.config;

import com.laimory.server.timeline.service.TimelineDraftCleanupWorkerProperties;
import com.laimory.server.timeline.service.TimelineOrphanItemSweeperProperties;
import com.laimory.server.timeline.service.TimelinePhotoDeleteWorkerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 스케줄 trigger와 실제 S3 삭제 I/O를 분리하는 bounded worker executor. */
@Configuration
public class TimelineWorkerExecutorConfig {

    @Bean(name = "timelineDraftCleanupWorkerExecutor", defaultCandidate = false)
    public ThreadPoolTaskExecutor timelineDraftCleanupWorkerExecutor(
            TimelineDraftCleanupWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerCount());
        executor.setMaxPoolSize(properties.getWorkerCount());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("draft-cleanup-");
        return executor;
    }

    @Bean(name = "timelinePhotoDeleteWorkerExecutor", defaultCandidate = false)
    public ThreadPoolTaskExecutor timelinePhotoDeleteWorkerExecutor(
            TimelinePhotoDeleteWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerCount());
        executor.setMaxPoolSize(properties.getWorkerCount());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("photo-delete-");
        return executor;
    }

    /** orphan 스위퍼의 PHOTO S3 삭제(#551). slot당 요청 1회라 queue 없이 거절되면 Item이 남아 다음 실행이 다시 집는다. */
    @Bean(name = "timelineOrphanSweepExecutor", defaultCandidate = false)
    public ThreadPoolTaskExecutor timelineOrphanSweepExecutor(TimelineOrphanItemSweeperProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkerCount());
        executor.setMaxPoolSize(properties.getWorkerCount());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("orphan-sweep-");
        return executor;
    }
}
