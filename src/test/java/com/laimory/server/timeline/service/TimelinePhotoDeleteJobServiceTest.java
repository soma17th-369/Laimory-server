package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelinePhotoDeleteJobStatus;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
    private TimelineEventItemService timelineEventItemService;

    @Mock
    private TimelineItemService timelineItemService;

    @Mock
    private TimelinePhotoDeleteJob first;

    @Mock
    private TimelinePhotoDeleteJob second;

    @Mock
    private TimelineItem item;

    private TimelinePhotoDeleteJobService service;

    @BeforeEach
    void setUp() {
        service = new TimelinePhotoDeleteJobService(
                repository, timelineEventItemService, timelineItemService, CLOCK);
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
        when(repository.findClaimableForUpdateSkipLocked(windowStart, todayStart, 250))
                .thenReturn(List.of(first, second));
        when(repository.markProcessing(
                List.of(11L, 12L), TimelinePhotoDeleteJobStatus.PROCESSING, claimedAt))
                .thenReturn(2);

        assertThat(service.claimEligible(250)).containsExactly(first, second);

        verify(repository).markProcessing(
                List.of(11L, 12L), TimelinePhotoDeleteJobStatus.PROCESSING, claimedAt);
    }

    @Test
    void claimEligible_validatesBatchAndDoesNotUpdateEmptySelection() {
        when(repository.findClaimableForUpdateSkipLocked(
                LocalDateTime.of(2026, 8, 11, 0, 0), LocalDateTime.of(2026, 8, 14, 0, 0), 250))
                .thenReturn(List.of());

        assertThat(service.claimEligible(250)).isEmpty();
        verify(repository, never()).markProcessing(
                List.of(), TimelinePhotoDeleteJobStatus.PROCESSING,
                LocalDateTime.of(2026, 8, 14, 3, 30));
        assertThatIllegalArgumentException().isThrownBy(() -> service.claimEligible(0));
        assertThatIllegalArgumentException().isThrownBy(() -> service.claimEligible(1_001));
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
    void cancelPendingForRelink_deletesJobAndReturnsPreservedPhotoItem() {
        when(repository.findByObjectKeyForUpdate("hash/photos/photo.jpg"))
                .thenReturn(Optional.of(first));
        when(first.getStatus()).thenReturn(TimelinePhotoDeleteJobStatus.PENDING);
        when(first.getTimelineItemId()).thenReturn(101L);
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(timelineItemService.findById(101L)).thenReturn(Optional.of(item));
        when(item.getItemType()).thenReturn(ItemType.PHOTO);
        when(item.getRawId()).thenReturn("raw-photo");
        when(item.getTimelineItemId()).thenReturn(101L);
        when(repository.deleteAllByJobIdIn(List.of(11L))).thenReturn(1);

        assertThat(service.cancelPendingForRelink("hash/photos/photo.jpg", "raw-photo"))
                .contains(101L);
    }

    @Test
    void cancelPendingForRelink_rejectsProcessingJobClaimedToday() {
        when(repository.findByObjectKeyForUpdate("hash/photos/photo.jpg"))
                .thenReturn(Optional.of(first));
        when(first.getStatus()).thenReturn(TimelinePhotoDeleteJobStatus.PROCESSING);
        // 오늘 00:00 경계 포함(>= todayStart)이 active다.
        when(first.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, 8, 14, 0, 0));

        assertThatThrownBy(() -> service.cancelPendingForRelink("hash/photos/photo.jpg", "raw-photo"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getExceptionType())
                                .isEqualTo(ExceptionType.PHOTO_DELETE_IN_PROGRESS));

        verify(timelineItemService, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void cancelPendingForRelink_cancelsStaleProcessingJobFromPreviousDay() {
        when(repository.findByObjectKeyForUpdate("hash/photos/photo.jpg"))
                .thenReturn(Optional.of(first));
        when(first.getStatus()).thenReturn(TimelinePhotoDeleteJobStatus.PROCESSING);
        when(first.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, 8, 13, 23, 59));
        when(first.getTimelineItemId()).thenReturn(101L);
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(timelineItemService.findById(101L)).thenReturn(Optional.of(item));
        when(item.getItemType()).thenReturn(ItemType.PHOTO);
        when(item.getRawId()).thenReturn("raw-photo");
        when(item.getTimelineItemId()).thenReturn(101L);
        when(repository.deleteAllByJobIdIn(List.of(11L))).thenReturn(1);

        assertThat(service.cancelPendingForRelink("hash/photos/photo.jpg", "raw-photo"))
                .contains(101L);
    }

    @Test
    void deleteByIds_skipsEmptyInput() {
        assertThat(service.deleteByIds(List.of())).isZero();

        verify(repository, never()).deleteAllByJobIdIn(List.of());
    }

    @Test
    void retainOrphanJobs_cancelsRelinkedJobsAndReturnsOnlyCurrentOrphans() {
        when(first.getTimelineItemId()).thenReturn(101L);
        when(second.getTimelineItemId()).thenReturn(102L);
        when(second.getTimelinePhotoDeleteJobId()).thenReturn(12L);
        when(timelineEventItemService.findByTimelineItemIds(List.of(101L, 102L)))
                .thenReturn(List.of(TimelineEventItem.of(22L, 102L)));
        when(repository.deleteAllByJobIdIn(List.of(12L))).thenReturn(1);

        TimelinePhotoDeleteJobService.ValidationResult result =
                service.retainOrphanJobs(List.of(first, second));

        assertThat(result.orphanJobs()).containsExactly(first);
        assertThat(result.cancelledJobs()).isEqualTo(1);
    }

    @Test
    void retainOrphanJobs_keepsAllJobsWhenNoItemIsLinked() {
        when(first.getTimelineItemId()).thenReturn(101L);
        when(timelineEventItemService.findByTimelineItemIds(List.of(101L))).thenReturn(List.of());

        TimelinePhotoDeleteJobService.ValidationResult result =
                service.retainOrphanJobs(List.of(first));

        assertThat(result.orphanJobs()).containsExactly(first);
        assertThat(result.cancelledJobs()).isZero();
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
    void lifecycleOperations_emptyInputAreNoOp() {
        assertThat(service.retainOrphanJobs(List.of()).orphanJobs()).isEmpty();
        assertThat(service.completeSucceeded(List.of())).isZero();

        verifyNoInteractions(timelineEventItemService, timelineItemService);
    }
}
