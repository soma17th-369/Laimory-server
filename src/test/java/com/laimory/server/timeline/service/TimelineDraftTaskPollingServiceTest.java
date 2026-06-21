package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** 폴링 오케스트레이터 단위 검증. PROCESSING/FAILED/SUCCESS 분기 + 404. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class TimelineDraftTaskPollingServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private DailyTimelineService dailyTimelineService;

    @InjectMocks
    private TimelineDraftTaskPollingService service;

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);

    @Test
    void poll_processing_returnsProcessing() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.processing(DATE, "hash")));

        DraftTaskStatusResponse res = service.poll("v1", "t");

        assertThat(res.status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(res.result()).isNull();
        assertThat(res.error()).isNull();
    }

    @Test
    void poll_failed_returnsError() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.failed(DATE, "boom")));

        DraftTaskStatusResponse res = service.poll("v1", "t");

        assertThat(res.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(res.error()).isEqualTo("boom");
        assertThat(res.result()).isNull();
    }

    @Test
    void poll_success_assemblesTimeline() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.success(DATE)));
        DailyRecord record = DailyRecord.createDraft(0L, DATE);
        ReflectionTestUtils.setField(record, "dailyRecordId", 42L);
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.of(record));
        DailyTimelineResponse timeline = new DailyTimelineResponse(DATE, null, List.of());
        when(dailyTimelineService.getDailyTimeline(42L)).thenReturn(timeline);

        DraftTaskStatusResponse res = service.poll("v1", "t");

        assertThat(res.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(res.result()).isSameAs(timeline);
    }

    @Test
    void poll_notFound_throws404() {
        when(timelineTaskService.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.poll("v1", "missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
