package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.TimelinePhotoDeleteJobStatus;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimelinePhotoDeleteJobServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-13T18:30:00Z"), ZoneOffset.UTC);

    @Mock
    private TimelinePhotoDeleteJobRepository repository;

    @Mock
    private TimelineItemService timelineItemService;

    @Mock
    private TimelinePhotoDeleteJob first;

    @Mock
    private TimelinePhotoDeleteJob second;

    private TimelinePhotoDeleteJobService service;

    @BeforeEach
    void setUp() {
        service = new TimelinePhotoDeleteJobService(repository, timelineItemService, CLOCK);
    }

    @Test
    void insertIfAbsent_writesSingleSeoulAuditTimeAndReportsWhetherInserted() {
        LocalDateTime auditAt = LocalDateTime.of(2026, 8, 14, 3, 30);
        when(repository.insertIfAbsent(1L, "hash/photos/photo.jpg", auditAt)).thenReturn(1, 0);

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
                0L, "hash/photos/photo.jpg", LocalDateTime.of(2026, 8, 14, 3, 30));
        verify(repository, never()).insertIfAbsent(
                1L, "한글/photos/photo.jpg", LocalDateTime.of(2026, 8, 14, 3, 30));
        verify(repository, never()).insertIfAbsent(
                1L, " ", LocalDateTime.of(2026, 8, 14, 3, 30));
    }

    @Test
    void claimEligible_queriesSeoulThreeDayWindowAndMarksUpdatedAtWithClaimTime() {
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(second.getTimelinePhotoDeleteJobId()).thenReturn(12L);
        LocalDateTime windowStart = LocalDateTime.of(2026, 8, 11, 0, 0);
        LocalDateTime todayStart = LocalDateTime.of(2026, 8, 14, 0, 0);
        LocalDateTime claimedAt = LocalDateTime.of(2026, 8, 14, 3, 30);
        when(repository.findClaimable(windowStart, todayStart, 0, 1, 250))
                .thenReturn(List.of(first, second));
        when(repository.markProcessing(
                List.of(11L, 12L), TimelinePhotoDeleteJobStatus.PROCESSING, claimedAt))
                .thenReturn(2);

        assertThat(service.claimEligible(0, 1, 250)).containsExactly(first, second);

        verify(repository).markProcessing(
                List.of(11L, 12L), TimelinePhotoDeleteJobStatus.PROCESSING, claimedAt);
    }

    @Test
    void missingSelectedJobAbortsClaimBeforeReturningToWorker() {
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(second.getTimelinePhotoDeleteJobId()).thenReturn(12L);
        var windowStart = LocalDateTime.of(2026, 8, 11, 0, 0);
        var todayStart = LocalDateTime.of(2026, 8, 14, 0, 0);
        when(repository.findClaimable(windowStart, todayStart, 0, 2, 250))
                .thenReturn(List.of(first, second));
        when(repository.markProcessing(List.of(11L, 12L), TimelinePhotoDeleteJobStatus.PROCESSING,
                LocalDateTime.of(2026, 8, 14, 3, 30))).thenReturn(1);
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> service.claimEligible(0, 2, 250))
                .withMessage("PHOTO delete job claim count mismatch");
    }

    @Test
    void claimEligible_validatesBatchAndDoesNotUpdateEmptySelection() {
        when(repository.findClaimable(
                LocalDateTime.of(2026, 8, 11, 0, 0), LocalDateTime.of(2026, 8, 14, 0, 0), 0, 1, 250))
                .thenReturn(List.of());

        assertThat(service.claimEligible(0, 1, 250)).isEmpty();
        verify(repository, never()).markProcessing(
                List.of(), TimelinePhotoDeleteJobStatus.PROCESSING,
                LocalDateTime.of(2026, 8, 14, 3, 30));
        assertThatIllegalArgumentException().isThrownBy(() -> service.claimEligible(0, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> service.claimEligible(0, 1, 1_001));
    }

    @Test
    void countExpired_usesSameSeoulWindowBoundaryAsClaim() {
        when(repository.countCreatedBefore(LocalDateTime.of(2026, 8, 11, 0, 0))).thenReturn(3L);

        assertThat(service.countExpired()).isEqualTo(3L);
    }

    @Test
    void markPendingForRetry_changesOnlyProcessingJobs() {
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(second.getTimelinePhotoDeleteJobId()).thenReturn(12L);
        when(repository.markPending(
                List.of(11L, 12L), TimelinePhotoDeleteJobStatus.PENDING,
                TimelinePhotoDeleteJobStatus.PROCESSING)).thenReturn(2);

        assertThat(service.markPendingForRetry(List.of(first, second))).isEqualTo(2);
    }

    @Test
    void deleteByIds_skipsEmptyInput() {
        assertThat(service.deleteByIds(List.of())).isZero();

        verify(repository, never()).deleteAllByJobIdIn(List.of());
    }

    @Test
    void completeSucceeded_deletesJobsBeforeOriginalItemsWithoutPreLockRead() {
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(second.getTimelinePhotoDeleteJobId()).thenReturn(12L);
        when(first.getTimelineItemId()).thenReturn(101L);
        when(second.getTimelineItemId()).thenReturn(102L);
        when(repository.deleteAllByJobIdIn(List.of(11L, 12L))).thenReturn(2);

        assertThat(service.completeSucceeded(List.of(first, second))).isEqualTo(2);

        InOrder order = inOrder(repository, timelineItemService);
        order.verify(repository).deleteAllByJobIdIn(List.of(11L, 12L));
        order.verify(timelineItemService).deleteByIds(List.of(101L, 102L));
    }

    @Test
    void completeSucceeded_alreadyCompletedRaceStillConvergesWithoutError() {
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(first.getTimelineItemId()).thenReturn(101L);
        when(repository.deleteAllByJobIdIn(List.of(11L))).thenReturn(0);

        assertThat(service.completeSucceeded(List.of(first))).isZero();

        verify(timelineItemService, never()).deleteByIds(List.of(101L));
    }

    @Test
    void completeSucceeded_partialJobDeleteRollsBackInsteadOfDeletingAmbiguousItems() {
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(second.getTimelinePhotoDeleteJobId()).thenReturn(12L);
        when(first.getTimelineItemId()).thenReturn(101L);
        when(second.getTimelineItemId()).thenReturn(102L);
        when(repository.deleteAllByJobIdIn(List.of(11L, 12L))).thenReturn(1);

        assertThatThrownBy(() -> service.completeSucceeded(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PHOTO delete job completion count mismatch");

        verify(timelineItemService, never()).deleteByIds(List.of(101L, 102L));
    }

    @Test
    void completeSucceeded_skipsEmptyInput() {
        assertThat(service.completeSucceeded(List.of())).isZero();

        verifyNoInteractions(timelineItemService);
    }
}
