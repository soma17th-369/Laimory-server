package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
    void defaultsRunAfterPhotoDeleteAndBeforeDraftCleanup() {
        ZoneId zone = ZoneId.of(DEFAULT_ZONE);
        ZonedDateTime beforeRun = ZonedDateTime.of(2026, 8, 6, 3, 29, 59, 0, zone);

        assertThat(CronExpression.parse(DEFAULT_CRON).next(beforeRun))
                .isEqualTo(ZonedDateTime.of(2026, 8, 6, 3, 30, 0, 0, zone));
        // PHOTO 삭제(03:00, 최대 run 60s)가 끝난 뒤이고 draft cleanup(04:00)보다 앞이다.
        assertThat(CronExpression.parse("0 0 3 * * *").next(beforeRun.minusHours(1)))
                .isBefore(ZonedDateTime.of(2026, 8, 6, 3, 30, 0, 0, zone));
    }

    @Test
    void disabledSweeperDoesNotTouchDatabase() {
        TimelineOrphanItemSweepService sweepService = mock(TimelineOrphanItemSweepService.class);
        TimelineOrphanItemSweeper sweeper = new TimelineOrphanItemSweeper(sweepService,
                new TimelineOrphanItemSweeperProperties(false, 250, 4, Duration.ofSeconds(60)));

        sweeper.sweepOrphanItems();

        verifyNoInteractions(sweepService);
    }

    @Test
    void terminatesRunOnlyWhenScanIsExhausted() {
        TimelineOrphanItemSweepService sweepService = mock(TimelineOrphanItemSweepService.class);
        // claim이 0인 batch(다른 host가 선점)를 만나도 커서를 올려 계속 훑어야 한다.
        org.mockito.Mockito.when(sweepService.sweepBatch(anyLong(), anyInt()))
                .thenReturn(new TimelineOrphanItemSweepService.SweepBatchResult(
                        2, 0, 2, 0, 0, 0, 0, 0, 0, 10L, false))
                .thenReturn(new TimelineOrphanItemSweepService.SweepBatchResult(
                        1, 1, 0, 0, 0, 0, 0, 0, 1, 20L, false))
                .thenReturn(new TimelineOrphanItemSweepService.SweepBatchResult(
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 20L, true));
        TimelineOrphanItemSweeper sweeper = new TimelineOrphanItemSweeper(sweepService,
                new TimelineOrphanItemSweeperProperties(true, 250, 4, Duration.ofSeconds(60)));

        sweeper.sweepOrphanItems();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(sweepService);
        order.verify(sweepService).sweepBatch(0L, 250);
        order.verify(sweepService).sweepBatch(10L, 250);
        order.verify(sweepService).sweepBatch(20L, 250);
        order.verifyNoMoreInteractions();
    }
}
