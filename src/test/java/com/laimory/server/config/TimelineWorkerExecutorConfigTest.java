package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.timeline.service.TimelineDraftCleanupWorkerProperties;
import com.laimory.server.timeline.service.TimelineOrphanItemSweeperProperties;
import java.time.Duration;
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

    @Test
    void draftExecutorCapacityEqualsWorkerCount() {
        var config = new TimelineWorkerExecutorConfig();
        for (int count : new int[] {1, 2}) {
            var executor = config.timelineDraftCleanupWorkerExecutor(new TimelineDraftCleanupWorkerProperties(true, 7, 250, 1, 2, count));
            assertThat(executor.getCorePoolSize()).isEqualTo(count);
            assertThat(executor.getMaxPoolSize()).isEqualTo(count);
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
            .withUserConfiguration(AsyncConfig.class, TimelineWorkerExecutorConfig.class, AsyncProbeConfig.class)
            .withBean(TimelineDraftCleanupWorkerProperties.class, () ->
                    new TimelineDraftCleanupWorkerProperties(true, 7, 250, 0, 2, 1))
            .withBean(TimelineOrphanItemSweeperProperties.class, () ->
                    new TimelineOrphanItemSweeperProperties(true, 250, 0, 2, 1));

    @Test
    void workerExecutorsCoexistWithBootDefaultAndAsyncUsesBootExecutor() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("applicationTaskExecutor");
            assertThat(context).hasBean("timelineDraftCleanupWorkerExecutor");
            assertThat(context).hasBean("timelineOrphanSweepExecutor");

            ThreadPoolTaskExecutor application = context.getBean(
                    "applicationTaskExecutor", ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor draft = context.getBean(
                    "timelineDraftCleanupWorkerExecutor", ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor orphan = context.getBean(
                    "timelineOrphanSweepExecutor", ThreadPoolTaskExecutor.class);
            assertThat(draft).isNotSameAs(application);
            assertThat(orphan).isNotSameAs(application);

            AsyncProbe probe = context.getBean(AsyncProbe.class);
            assertThat(probe.threadName().join()).startsWith("task-");
            assertThat(CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), draft).join())
                    .startsWith("draft-cleanup-");
            assertThat(CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), orphan).join())
                    .startsWith("orphan-sweep-");
        });
    }

    @Test
    void orphanSweepExecutorCapacityEqualsWorkerCountWithoutQueue() {
        var config = new TimelineWorkerExecutorConfig();
        for (int count : new int[] {1, 2}) {
            var executor = config.timelineOrphanSweepExecutor(new TimelineOrphanItemSweeperProperties(true, 250, 1, 2, count));
            assertThat(executor.getCorePoolSize()).isEqualTo(count);
            assertThat(executor.getMaxPoolSize()).isEqualTo(count);
            assertThat(executor.getQueueCapacity()).isZero();
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
