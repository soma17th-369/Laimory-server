package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimelinePhotoDeleteValidationServiceTest {

    @Mock
    private TimelineEventItemService timelineEventItemService;

    @Mock
    private TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;

    @Mock
    private TimelinePhotoDeleteJob orphan;

    @Mock
    private TimelinePhotoDeleteJob relinked;

    private TimelinePhotoDeleteValidationService service;

    @BeforeEach
    void setUp() {
        service = new TimelinePhotoDeleteValidationService(
                timelineEventItemService, timelinePhotoDeleteJobService);
    }

    @Test
    void retainOrphanJobs_cancelsRelinkedJobsAndReturnsOnlyCurrentOrphans() {
        when(orphan.getTimelineItemId()).thenReturn(101L);
        when(relinked.getTimelineItemId()).thenReturn(102L);
        when(relinked.getTimelinePhotoDeleteJobId()).thenReturn(12L);
        when(timelineEventItemService.findByTimelineItemIds(List.of(101L, 102L)))
                .thenReturn(List.of(TimelineEventItem.of(22L, 102L)));
        when(timelinePhotoDeleteJobService.deleteByIds(List.of(12L))).thenReturn(1);

        TimelinePhotoDeleteValidationService.ValidationResult result =
                service.retainOrphanJobs(List.of(orphan, relinked));

        assertThat(result.orphanJobs()).containsExactly(orphan);
        assertThat(result.cancelledJobs()).isEqualTo(1);
    }

    @Test
    void retainOrphanJobs_keepsAllJobsWhenNoItemIsLinked() {
        when(orphan.getTimelineItemId()).thenReturn(101L);
        when(timelineEventItemService.findByTimelineItemIds(List.of(101L))).thenReturn(List.of());

        TimelinePhotoDeleteValidationService.ValidationResult result =
                service.retainOrphanJobs(List.of(orphan));

        assertThat(result.orphanJobs()).containsExactly(orphan);
        assertThat(result.cancelledJobs()).isZero();
        verify(timelinePhotoDeleteJobService, never()).deleteByIds(List.of());
    }
}
