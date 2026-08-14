package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
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
class TimelinePhotoDeleteJobServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-13T18:30:00Z"), ZoneOffset.UTC);

    @Mock
    private TimelinePhotoDeleteJobRepository repository;

    @Mock
    private TimelinePhotoDeleteJob first;

    @Mock
    private TimelinePhotoDeleteJob second;

    private TimelinePhotoDeleteJobService service;

    @BeforeEach
    void setUp() {
        service = new TimelinePhotoDeleteJobService(repository, CLOCK);
    }

    @Test
    void insertIfAbsent_defersNewJobToNextSeoulCalendarDayAndReportsWhetherInserted() {
        LocalDateTime initialAvailableAt = LocalDateTime.of(2026, 8, 15, 0, 0);
        when(repository.insertIfAbsent(1L, "hash/photos/photo.jpg", initialAvailableAt)).thenReturn(1, 0);

        assertThat(service.insertIfAbsent(1L, "hash/photos/photo.jpg")).isTrue();
        assertThat(service.insertIfAbsent(1L, "hash/photos/photo.jpg")).isFalse();
    }

    @Test
    void insertIfAbsent_rejectsValuesThatInsertIgnoreCouldOtherwiseCoerce() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.insertIfAbsent(0L, "hash/photos/photo.jpg"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.insertIfAbsent(1L, "한글/photos/photo.jpg"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.insertIfAbsent(1L, " "));

        verify(repository, never()).insertIfAbsent(
                0L, "hash/photos/photo.jpg", LocalDateTime.of(2026, 8, 15, 0, 0));
        verify(repository, never()).insertIfAbsent(
                1L, "한글/photos/photo.jpg", LocalDateTime.of(2026, 8, 15, 0, 0));
        verify(repository, never()).insertIfAbsent(
                1L, " ", LocalDateTime.of(2026, 8, 15, 0, 0));
    }

    @Test
    void claimEligible_usesSeoulClockAndDefersToNextCalendarDayMidnight() {
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(second.getTimelinePhotoDeleteJobId()).thenReturn(12L);
        LocalDateTime eligibleAt = LocalDateTime.of(2026, 8, 14, 3, 30);
        when(repository.findEligibleForUpdateSkipLocked(eligibleAt, 250))
                .thenReturn(List.of(first, second));
        when(repository.deferUntil(List.of(11L, 12L), LocalDateTime.of(2026, 8, 15, 0, 0)))
                .thenReturn(2);

        assertThat(service.claimEligible(250)).containsExactly(first, second);

        verify(repository).deferUntil(
                List.of(11L, 12L), LocalDateTime.of(2026, 8, 15, 0, 0));
    }

    @Test
    void claimEligible_validatesBatchAndDoesNotUpdateEmptySelection() {
        when(repository.findEligibleForUpdateSkipLocked(
                LocalDateTime.of(2026, 8, 14, 3, 30), 250))
                .thenReturn(List.of());

        assertThat(service.claimEligible(250)).isEmpty();
        verify(repository, never()).deferUntil(List.of(), LocalDateTime.of(2026, 8, 15, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> service.claimEligible(0));
        assertThatIllegalArgumentException().isThrownBy(() -> service.claimEligible(1_001));
    }

    @Test
    void deleteByIds_skipsEmptyInput() {
        assertThat(service.deleteByIds(List.of())).isZero();

        verify(repository, never()).deleteAllByJobIdIn(List.of());
    }
}
