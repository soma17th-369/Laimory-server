package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemBatchRepository;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimelineDraftSourceItemServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-13T18:30:00Z"), ZoneOffset.UTC);

    @Mock
    private TimelineDraftSourceItemRepository repository;

    @Mock
    private TimelineDraftSourceItemBatchRepository batchRepository;

    @Mock
    private TimelineDraftSourceItem row;

    private TimelineDraftSourceItemService service;

    @BeforeEach
    void setUp() {
        service = new TimelineDraftSourceItemService(repository, batchRepository, CLOCK);
    }

    @Test
    void claimExpired_usesSeoulEligibilityAndDefersToNextCalendarDayMidnight() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 7, 4, 0);
        LocalDateTime eligibleAt = LocalDateTime.of(2026, 8, 14, 3, 30);
        when(row.getTimelineDraftSourceItemId()).thenReturn(11L);
        when(repository.findExpiredForUpdateSkipLocked(cutoff, eligibleAt, 250))
                .thenReturn(List.of(row));
        when(repository.deferCleanupUntil(List.of(11L), LocalDateTime.of(2026, 8, 15, 0, 0)))
                .thenReturn(1);

        assertThat(service.claimExpired(cutoff, 250)).containsExactly(row);

        verify(repository).deferCleanupUntil(
                List.of(11L), LocalDateTime.of(2026, 8, 15, 0, 0));
    }

    @Test
    void claimExpired_validatesBatchAndSkipsUpdateForEmptySelection() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 7, 4, 0);
        when(repository.findExpiredForUpdateSkipLocked(
                cutoff, LocalDateTime.of(2026, 8, 14, 3, 30), 250))
                .thenReturn(List.of());

        assertThat(service.claimExpired(cutoff, 250)).isEmpty();
        verify(repository, never()).deferCleanupUntil(
                List.of(), LocalDateTime.of(2026, 8, 15, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> service.claimExpired(cutoff, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> service.claimExpired(cutoff, 1_001));
    }
}
