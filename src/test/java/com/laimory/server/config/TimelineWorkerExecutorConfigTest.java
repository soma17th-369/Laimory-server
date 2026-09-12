package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.timeline.service.TimelineDraftCleanupWorkerProperties;
import com.laimory.server.timeline.service.TimelinePhotoDeleteWorkerProperties;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 전용 worker executor가 Boot 기본 applicationTaskExecutor와 기존 무지정 @Async를 가로채지 않는지 검증한다. */
class TimelineWorkerExecutorConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
            .withUserConfiguration(AsyncConfig.class, TimelineWorkerExecutorConfig.class, AsyncProbeConfig.class)
            .withBean(TimelinePhotoDeleteWorkerProperties.class, () ->
                    new TimelinePhotoDeleteWorkerProperties(true, 250, 0, 2, 1))
            .withBean(TimelineDraftCleanupWorkerProperties.class, () ->
                    new TimelineDraftCleanupWorkerProperties(true, 7, 250, 0, 2, 1));

    @Test
    void workerExecutorsCoexistWithBootDefaultAndAsyncUsesBootExecutor() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("applicationTaskExecutor");
            assertThat(context).hasBean("timelinePhotoDeleteWorkerExecutor");
            assertThat(context).hasBean("timelineDraftCleanupWorkerExecutor");

            ThreadPoolTaskExecutor application = context.getBean(
                    "applicationTaskExecutor", ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor photo = context.getBean(
                    "timelinePhotoDeleteWorkerExecutor", ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor draft = context.getBean(
                    "timelineDraftCleanupWorkerExecutor", ThreadPoolTaskExecutor.class);
            assertThat(photo).isNotSameAs(application);
            assertThat(draft).isNotSameAs(application);

            AsyncProbe probe = context.getBean(AsyncProbe.class);
            assertThat(probe.threadName().join()).startsWith("task-");
            assertThat(CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), photo).join())
                    .startsWith("photo-delete-");
            assertThat(CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), draft).join())
                    .startsWith("draft-cleanup-");
        });
    }

    @Test
    void executorCapacityEqualsWorkerCount() {
        var config = new TimelineWorkerExecutorConfig();
        for (int count : new int[] {1, 2}) {
            var photo = config.timelinePhotoDeleteWorkerExecutor(
                    new TimelinePhotoDeleteWorkerProperties(true, 250, 1, 2, count));
            var draft = config.timelineDraftCleanupWorkerExecutor(
                    new TimelineDraftCleanupWorkerProperties(true, 7, 250, 1, 2, count));
            assertThat(photo.getCorePoolSize()).isEqualTo(count);
            assertThat(photo.getMaxPoolSize()).isEqualTo(count);
            assertThat(draft.getCorePoolSize()).isEqualTo(count);
            assertThat(draft.getMaxPoolSize()).isEqualTo(count);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AsyncProbeConfig {

        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    static class AsyncProbe {

        @Async
        CompletableFuture<String> threadName() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }
}
