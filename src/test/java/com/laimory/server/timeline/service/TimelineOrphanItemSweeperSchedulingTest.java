package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.laimory.server.timeline.photo.S3PhotoStorageService;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

class TimelineOrphanItemSweeperSchedulingTest {

    private static final String DEFAULT_CRON = "0 30 3 * * *";
    private static final String DEFAULT_ZONE = "Asia/Seoul";

    @Test
    void usesConfigurableDailyCronWithoutFixedDelay() throws NoSuchMethodException {
        Method method = TimelineOrphanItemSweeper.class.getDeclaredMethod("sweepOrphanItems");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${app.timeline.orphan-sweep.cron:" + DEFAULT_CRON + "}");
        assertThat(scheduled.zone()).isEqualTo("${app.timeline.orphan-sweep.zone:" + DEFAULT_ZONE + "}");
        assertThat(scheduled.fixedDelayString()).isEmpty();
    }

    @Test
    void defaultsRunAt0330BeforeDraftCleanup() {
        ZoneId zone = ZoneId.of(DEFAULT_ZONE);
        ZonedDateTime beforeRun = ZonedDateTime.of(2026, 8, 6, 3, 29, 59, 0, zone);

        assertThat(CronExpression.parse(DEFAULT_CRON).next(beforeRun))
                .isEqualTo(ZonedDateTime.of(2026, 8, 6, 3, 30, 0, 0, zone));
    }

    @Test
    void disabledSweeperDoesNotTouchDatabase() {
        TimelineOrphanItemSweepService sweepService = mock(TimelineOrphanItemSweepService.class);
        TimelineOrphanItemSweeper sweeper = new TimelineOrphanItemSweeper(sweepService,
                mock(S3PhotoStorageService.class), new TimelineOrphanItemSweeperProperties(false, 250, 0, 2, 1),
                Runnable::run);

        sweeper.sweepOrphanItems();

        verifyNoInteractions(sweepService);
    }

    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @Test
    void reportsStaleCountEvenWhenCurrentBatchIsEmpty(org.springframework.boot.test.system.CapturedOutput output) {
        TimelineOrphanItemSweepService service = mock(TimelineOrphanItemSweepService.class);
        org.mockito.Mockito.when(service.countStaleObservedOrphans(1, 2)).thenReturn(3L);
        var sweeper = new TimelineOrphanItemSweeper(service, mock(S3PhotoStorageService.class),
                new TimelineOrphanItemSweeperProperties(true, 250, 1, 2, 1), Runnable::run);
        sweeper.sweepOrphanItems();
        // 운영 logback 설정이 로드된 JVM에서는 로그가 AsyncAppender 워커 스레드로 써지므로(#497) 캡처는 eventual이다.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(output).contains("orphan Item 최초 관측 후 72시간 잔존: workerIndex=1 count=3"));
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .sweepBatch(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void eachSlotObservesOnceAndAlertsAfterFailedProcessing() {
        TimelineOrphanItemSweepService service = mock(TimelineOrphanItemSweepService.class);
        org.mockito.Mockito.when(service.observeBatch(2, 4, 1)).thenReturn(java.util.List.of(11L));
        org.mockito.Mockito.when(service.observeBatch(3, 4, 1)).thenReturn(java.util.List.of(12L));
        org.mockito.Mockito.when(service.sweepBatch(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new IllegalStateException("rollback"));
        var sweeper = new TimelineOrphanItemSweeper(service, mock(S3PhotoStorageService.class),
                new TimelineOrphanItemSweeperProperties(true, 1, 1, 2, 2), Runnable::run);
        sweeper.sweepOrphanItems();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(service);
        order.verify(service).observeBatch(2, 4, 1);
        order.verify(service).sweepBatch(java.util.List.of(11L));
        order.verify(service).countStaleObservedOrphans(2, 4);
        order.verify(service).observeBatch(3, 4, 1);
        order.verify(service).sweepBatch(java.util.List.of(12L));
        order.verify(service).countStaleObservedOrphans(3, 4);
        order.verifyNoMoreInteractions();
    }
}
