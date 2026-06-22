package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** cutoff가 고정 Clock과 설정된 retentionDays로 결정론적으로 계산되는지 단위 검증. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class TimelineDraftCleanupSchedulerTest {

    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-06-22T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void cleanup_deletesRowsOlderThanRetention() {
        TimelineDraftCleanupScheduler scheduler =
                new TimelineDraftCleanupScheduler(timelineDraftSourceItemService, FIXED);
        ReflectionTestUtils.setField(scheduler, "retentionDays", 7L);

        scheduler.cleanupExpiredDrafts();

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(timelineDraftSourceItemService, times(1)).deleteCreatedBefore(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue())
                .isEqualTo(LocalDateTime.now(FIXED).minusDays(7))
                .isEqualTo(LocalDateTime.of(2026, 6, 15, 3, 0));
    }

    @Test
    void cleanup_cutoffRespectsConfiguredRetention() {
        TimelineDraftCleanupScheduler scheduler =
                new TimelineDraftCleanupScheduler(timelineDraftSourceItemService, FIXED);
        ReflectionTestUtils.setField(scheduler, "retentionDays", 30L);

        scheduler.cleanupExpiredDrafts();

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(timelineDraftSourceItemService, times(1)).deleteCreatedBefore(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue())
                .isEqualTo(LocalDateTime.now(FIXED).minusDays(30))
                .isEqualTo(LocalDateTime.of(2026, 5, 23, 3, 0));
    }
}
