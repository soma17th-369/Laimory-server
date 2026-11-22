package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.config.AiDispatchConfig;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * TIMELINE 접수 경로 배선(#515, {@code app.ai.dispatch.timeline.route}). 경로 선택과 큐 설정 fail-fast를 실제 컨텍스트
 * 기동으로 확인한다. {@link SubmitterConsumer}가 접수 경로를 요구해야 매칭 빈이 없을 때(오타) 기동이 실제로 실패한다
 * ({@link AiDispatcherWiringTest}와 같은 이유).
 */
class TimelineDispatchRouteWiringTest {

    private static final String QUEUE_URL =
            "https://sqs.ap-northeast-2.amazonaws.com/123456789012/laimory-dev-ai-dispatch-timeline";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(TimelineTaskService.class, () -> Mockito.mock(TimelineTaskService.class))
            .withBean(TimelineTaskLossTimerService.class, () -> Mockito.mock(TimelineTaskLossTimerService.class))
            .withBean(TimelineAiDispatcher.class, () -> Mockito.mock(TimelineAiDispatcher.class))
            .withBean(TimelineDispatchCompensation.class, () -> Mockito.mock(TimelineDispatchCompensation.class))
            .withUserConfiguration(
                    AiDispatchConfig.class,
                    InlineTimelineDispatchSubmitter.class,
                    QueuedTimelineDispatchSubmitter.class,
                    SubmitterConsumer.class);

    /** 접수 경로를 생성자에서 요구하는 test-only consumer(production {@code TimelineDraftTaskService} 대역). */
    static class SubmitterConsumer {
        SubmitterConsumer(TimelineDispatchSubmitter submitter) {
        }
    }

    @Test
    void routeMissingDefaultsToInlineWithoutQueue() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TimelineDispatchSubmitter.class);
            assertThat(context.getBean(TimelineDispatchSubmitter.class))
                    .isInstanceOf(InlineTimelineDispatchSubmitter.class);
            assertThat(context).doesNotHaveBean(SqsClient.class);
        });
    }

    @Test
    void queueRouteWiresQueuedSubmitterAndSqsClient() {
        runner.withPropertyValues("app.ai.dispatch.timeline.route=queue",
                        "app.ai.dispatch.queue.timeline-url=" + QUEUE_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TimelineDispatchSubmitter.class);
                    assertThat(context.getBean(TimelineDispatchSubmitter.class))
                            .isInstanceOf(QueuedTimelineDispatchSubmitter.class);
                    assertThat(context).hasSingleBean(SqsClient.class);
                });
    }

    @Test
    void uppercaseQueueRouteWiresQueueLikeOtherSwitches() {
        // @ConditionalOnProperty는 대소문자를 무시한다 — client 조건도 같아야 경로 빈과 client가 함께 생긴다.
        runner.withPropertyValues("app.ai.dispatch.timeline.route=QUEUE",
                        "app.ai.dispatch.queue.timeline-url=" + QUEUE_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SqsClient.class);
                    assertThat(context.getBean(TimelineDispatchSubmitter.class))
                            .isInstanceOf(QueuedTimelineDispatchSubmitter.class);
                });
    }

    @Test
    void queueRouteWithoutQueueUrlFailsAtStartup() {
        runner.withPropertyValues("app.ai.dispatch.timeline.route=queue")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("APP_AI_DISPATCH_QUEUE_TIMELINE_URL"));
    }

    @Test
    void queueUrlInAnotherRegionFailsAtStartup() {
        runner.withPropertyValues("app.ai.dispatch.timeline.route=queue",
                        "app.ai.dispatch.queue.timeline-url="
                                + "https://sqs.us-east-1.amazonaws.com/123456789012/laimory-dev-ai-dispatch-timeline")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("us-east-1"));
    }

    @Test
    void unknownRouteFailsAtStartup() {
        runner.withPropertyValues("app.ai.dispatch.timeline.route=queu")
                .run(context -> assertThat(context).hasFailed());
    }
}
