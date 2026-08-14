package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimelinePhotoDeleteCompletionServiceTest {

    @Mock
    private TimelinePhotoDeleteJobService jobService;

    @Mock
    private TimelineItemService timelineItemService;

    @Mock
    private TimelinePhotoDeleteJob first;

    @Mock
    private TimelinePhotoDeleteJob second;

    private TimelinePhotoDeleteCompletionService service;

    @BeforeEach
    void setUp() {
        service = new TimelinePhotoDeleteCompletionService(jobService, timelineItemService);
    }

    @Test
    void completeSucceeded_deletesJobsBeforeOriginalItemsWithoutPreLockRead() {
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(second.getTimelinePhotoDeleteJobId()).thenReturn(12L);
        when(first.getTimelineItemId()).thenReturn(101L);
        when(second.getTimelineItemId()).thenReturn(102L);
        when(jobService.deleteByIds(List.of(11L, 12L))).thenReturn(2);

        assertThat(service.completeSucceeded(List.of(first, second))).isEqualTo(2);

        InOrder order = inOrder(jobService, timelineItemService);
        order.verify(jobService).deleteByIds(List.of(11L, 12L));
        order.verify(timelineItemService).deleteByIds(List.of(101L, 102L));
    }

    @Test
    void completeSucceeded_alreadyCompletedRaceStillConvergesWithoutError() {
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(first.getTimelineItemId()).thenReturn(101L);
        when(jobService.deleteByIds(List.of(11L))).thenReturn(0);

        assertThat(service.completeSucceeded(List.of(first))).isZero();

        verify(jobService).deleteByIds(List.of(11L));
        verify(timelineItemService, never()).deleteByIds(List.of(101L));
    }

    @Test
    void completeSucceeded_partialJobDeleteRollsBackInsteadOfDeletingAmbiguousItems() {
        when(first.getTimelinePhotoDeleteJobId()).thenReturn(11L);
        when(second.getTimelinePhotoDeleteJobId()).thenReturn(12L);
        when(first.getTimelineItemId()).thenReturn(101L);
        when(second.getTimelineItemId()).thenReturn(102L);
        when(jobService.deleteByIds(List.of(11L, 12L))).thenReturn(1);

        assertThatThrownBy(() -> service.completeSucceeded(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PHOTO delete job completion count mismatch");

        verify(timelineItemService, never()).deleteByIds(List.of(101L, 102L));
    }

    @Test
    void completeSucceeded_emptyInputIsNoOp() {
        assertThat(service.completeSucceeded(List.of())).isZero();

        verifyNoInteractions(jobService, timelineItemService);
    }
}
