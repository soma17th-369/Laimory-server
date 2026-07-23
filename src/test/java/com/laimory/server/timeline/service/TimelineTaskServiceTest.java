package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.repository.TimelineTaskStore;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** task 상태 전이 계약 검증: TTL·dailyRecordId 보존·markFailed 멤버십 가드. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class TimelineTaskServiceTest {

    @Mock
    private TimelineTaskStore timelineTaskStore;
    @Mock
    private TimelineMetrics timelineMetrics;

    @InjectMocks
    private TimelineTaskService service;

    private static final long USER_ID = 7L;
    private static final long RECORD_ID = 42L;
    private static final Instant STARTED_AT = Instant.parse("2026-06-17T03:05:00Z");

    @Test
    void createProcessing_storesRecordIdAndStartedAtWithProcessingTtl() {
        // dailyRecordId는 PROCESSING부터 실린다(폴링·콜백 전이·guard 해제의 기준) + TTL 1시간.
        service.createProcessing("t", USER_ID, RECORD_ID, null, "hash", STARTED_AT);

        ArgumentCaptor<TimelineDraftTask> task = ArgumentCaptor.forClass(TimelineDraftTask.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(timelineTaskStore).save(eq("t"), task.capture(), ttl.capture());
        assertThat(task.getValue().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(task.getValue().dailyRecordId()).isEqualTo(RECORD_ID);
        assertThat(task.getValue().processingStartedAt()).isEqualTo(STARTED_AT);
        assertThat(task.getValue().userId()).isEqualTo(USER_ID);
        assertThat(ttl.getValue()).isEqualTo(Duration.ofHours(1));
        verify(timelineMetrics).recordDraftCreated();
    }

    @Test
    void markSuccess_discardsProcessingStartedAt_withTerminalTtl() {
        // PROCESSING 전용 lifecycle: terminal에는 시각·window를 보존하지 않는다(의도적 폐기) + terminal TTL 24시간.
        service.markSuccess("t", USER_ID, RECORD_ID, "hash");

        ArgumentCaptor<TimelineDraftTask> task = ArgumentCaptor.forClass(TimelineDraftTask.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(timelineTaskStore).save(eq("t"), task.capture(), ttl.capture());
        assertThat(task.getValue().processingStartedAt()).isNull();
        assertThat(task.getValue().timelineWindow()).isNull();
        // owner·dailyRecordId는 terminal에도 보존된다(폴링 소유권 대조·결과 조회용).
        assertThat(task.getValue().userId()).isEqualTo(USER_ID);
        assertThat(task.getValue().dailyRecordId()).isEqualTo(RECORD_ID);
        assertThat(ttl.getValue()).isEqualTo(Duration.ofHours(24));
        verify(timelineMetrics).recordTerminalSuccess();
    }

    @Test
    void markFailed_storesFailureCodeName() {
        service.markFailed("t", USER_ID, RECORD_ID, ErrorCode.ERROR_1009, "hash");

        ArgumentCaptor<TimelineDraftTask> task = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskStore).save(eq("t"), task.capture(), any(Duration.class));
        assertThat(task.getValue().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getValue().error()).isEqualTo("ERROR_1009");
        // FAILED도 PROCESSING 시각을 보존하지 않는다. owner·dailyRecordId는 보존된다.
        assertThat(task.getValue().processingStartedAt()).isNull();
        assertThat(task.getValue().userId()).isEqualTo(USER_ID);
        assertThat(task.getValue().dailyRecordId()).isEqualTo(RECORD_ID);
        verify(timelineMetrics).recordTerminalFailed();
    }

    @Test
    void markFailed_rejectsNonTaskFailureCode() {
        // HTTP 에러 코드(ERROR_0400 등)를 task 상태로 저장하는 오용은 시그니처+가드가 차단한다.
        assertThatThrownBy(() -> service.markFailed("t", USER_ID, RECORD_ID, ErrorCode.ERROR_0400, "hash"))
                .isInstanceOf(IllegalStateException.class);
        verify(timelineTaskStore, never()).save(any(), any(), any());
        verify(timelineMetrics, never()).recordTerminalFailed();
    }

    @Test
    void terminalMetric_isNotIncrementedWhenStoreFails() {
        doThrow(new RuntimeException("redis down")).when(timelineTaskStore).save(any(), any(), any());

        assertThatThrownBy(() -> service.markSuccess("t", USER_ID, RECORD_ID, "hash"))
                .isInstanceOf(RuntimeException.class);

        verify(timelineMetrics, never()).recordTerminalSuccess();
    }
}
