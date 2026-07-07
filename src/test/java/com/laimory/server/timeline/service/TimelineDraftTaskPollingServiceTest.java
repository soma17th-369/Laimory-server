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
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import org.springframework.test.util.ReflectionTestUtils;

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
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.processing(DATE, DATE.atTime(12, 0), "Asia/Seoul", null, "hash")));

        DraftTaskStatusResponse res = service.poll("v1", "t");

        assertThat(res.status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(res.result()).isNull();
        assertThat(res.error()).isNull();
    }

    @Test
    void poll_failed_returnsFailureCode() {
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.failed(DATE, ErrorCode.ERROR_1009.name(), "h")));

        DraftTaskStatusResponse res = service.poll("v1", "t");

        assertThat(res.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(res.error()).isEqualTo("ERROR_1009"); // body.error = 실패 분류 코드
        assertThat(res.result()).isNull();
    }

    @Test
    void poll_failed_legacyRawError_isReplacedNotLeaked() {
        // 과거(코드화 이전) 저장분의 raw 메시지는 그대로 내보내지 않고 ERROR_1011로 대체한다(read-side 유출 방어).
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.failed(DATE, "Connection refused: 10.0.32.99", "h")));

        DraftTaskStatusResponse res = service.poll("v1", "t");

        assertThat(res.error()).isEqualTo(ErrorCode.ERROR_1011.name());
        assertThat(res.error()).doesNotContain("10.0.32.99");
    }

    @Test
    void poll_success_assemblesTimeline() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.success(DATE, "h")));
        DailyRecord record = DailyRecord.createDraft(0L, DATE, DATE.atTime(12, 0), "Asia/Seoul");
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
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1001));
    }
}
