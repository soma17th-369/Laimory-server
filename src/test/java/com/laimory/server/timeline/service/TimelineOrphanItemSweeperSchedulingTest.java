package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
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
    void defaultsRunAfterPhotoDeleteAndBeforeDraftCleanup() {
        ZoneId zone = ZoneId.of(DEFAULT_ZONE);
        ZonedDateTime beforeRun = ZonedDateTime.of(2026, 8, 6, 3, 29, 59, 0, zone);

        assertThat(CronExpression.parse(DEFAULT_CRON).next(beforeRun))
                .isEqualTo(ZonedDateTime.of(2026, 8, 6, 3, 30, 0, 0, zone));
        // PHOTO 삭제의 정규 시작(03:00) 뒤이고 draft cleanup(04:00)보다 앞이다.
        assertThat(CronExpression.parse("0 0 3 * * *").next(beforeRun.minusHours(1)))
                .isBefore(ZonedDateTime.of(2026, 8, 6, 3, 30, 0, 0, zone));
    }

    @Test
    void disabledSweeperDoesNotTouchDatabase() {
        TimelineOrphanItemSweepService sweepService = mock(TimelineOrphanItemSweepService.class);
        TimelineOrphanItemSweeper sweeper = new TimelineOrphanItemSweeper(sweepService,
                new TimelineOrphanItemSweeperProperties(false, 250, 0, 2, 1));

        sweeper.sweepOrphanItems();

        verifyNoInteractions(sweepService);
    }

    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @Test
    void reportsStaleCountEvenWhenCurrentBatchIsEmpty(org.springframework.boot.test.system.CapturedOutput output) {
        TimelineOrphanItemSweepService service = mock(TimelineOrphanItemSweepService.class);
        org.mockito.Mockito.when(service.countStaleObservedOrphans(1, 2)).thenReturn(3L);
        var sweeper = new TimelineOrphanItemSweeper(service,
                new TimelineOrphanItemSweeperProperties(true, 250, 1, 2, 1));
        sweeper.sweepOrphanItems();
        assertThat(output).contains("orphan Item 최초 관측 후 72시간 잔존: workerIndex=1 count=3");
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
        var sweeper = new TimelineOrphanItemSweeper(service,
                new TimelineOrphanItemSweeperProperties(true, 1, 1, 2, 2));
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
