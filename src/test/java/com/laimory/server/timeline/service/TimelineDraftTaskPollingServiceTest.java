package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 폴링 오케스트레이터 단위 검증. PROCESSING/FAILED/SUCCESS 분기 + elapsedSeconds 계산 + 404. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class TimelineDraftTaskPollingServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private DailyTimelineService dailyTimelineService;
    @Mock
    private Clock clock;

    @InjectMocks
    private TimelineDraftTaskPollingService service;

    private static final long USER_ID = 7L;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    // 폴링 관측 시각(mock Clock) — PROCESSING 경과 시간의 "현재".
    private static final Instant NOW = Instant.parse("2026-06-17T03:10:00Z");

    private static TimelineDraftTask processingTask(Instant processingStartedAt) {
        return TimelineDraftTask.processing(USER_ID, 42L, null, "hash", processingStartedAt);
    }

    @Test
    void poll_processing_returnsElapsedWholeSeconds() {
        when(clock.instant()).thenReturn(NOW);
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(processingTask(NOW.minusSeconds(12))));

        DraftTaskStatusResponse res = service.poll("v1", USER_ID, "t");

        assertThat(res.status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(res.elapsedSeconds()).isEqualTo(12L);
        assertThat(res.result()).isNull();
        assertThat(res.error()).isNull();
    }

    @Test
    void poll_processing_truncatesFractionalSeconds() {
        // 완료된 초만: 12.9초 경과 → 12, 1초 미만 → 0.
        when(clock.instant()).thenReturn(NOW);
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(processingTask(NOW.minusMillis(12_900))));
        assertThat(service.poll("v1", USER_ID, "t").elapsedSeconds()).isEqualTo(12L);

        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(processingTask(NOW.minusMillis(500))));
        assertThat(service.poll("v1", USER_ID, "t").elapsedSeconds()).isEqualTo(0L);
    }

    @Test
    void poll_processing_futureTimestampClampsToZero() {
        // 시계 역행·future timestamp → 음수를 노출하지 않고 0으로 clamp.
        when(clock.instant()).thenReturn(NOW);
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(processingTask(NOW.plusSeconds(60))));

        assertThat(service.poll("v1", USER_ID, "t").elapsedSeconds()).isEqualTo(0L);
    }

    @Test
    void poll_processing_largeRange_returnsLongWithoutOverflow() {
        // int 범위를 훨씬 넘는 경과도 long seconds로 그대로 반환된다(int cast/millis 곱셈 회귀 방지).
        when(clock.instant()).thenReturn(NOW);
        Instant farPast = NOW.minusSeconds(10_000_000_000L); // ≈317년 > Integer.MAX_VALUE초
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask(farPast)));

        assertThat(service.poll("v1", USER_ID, "t").elapsedSeconds()).isEqualTo(10_000_000_000L);
    }

    @Test
    void poll_processing_legacyWithoutStartedAt_omitsElapsed() {
        // 배포 전 legacy PROCESSING(시각 부재) → 값을 추측·위조하지 않고 null(응답 key 생략).
        when(timelineTaskService.find("t")).thenReturn(Optional.of(processingTask(null)));

        DraftTaskStatusResponse res = service.poll("v1", USER_ID, "t");

        assertThat(res.status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(res.elapsedSeconds()).isNull();
    }

    @Test
    void poll_failed_returnsFailureCode() {
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.failed(USER_ID, 42L, ErrorCode.ERROR_1009.name(), "h")));

        DraftTaskStatusResponse res = service.poll("v1", USER_ID, "t");

        assertThat(res.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(res.error()).isEqualTo("ERROR_1009"); // body.error = 실패 분류 코드
        assertThat(res.result()).isNull();
        // 경과 시간은 PROCESSING 전용 — terminal은 null(응답 key 생략)이고 Clock도 읽지 않는다.
        assertThat(res.elapsedSeconds()).isNull();
        verifyNoInteractions(clock);
    }

    @Test
    void poll_failed_legacyRawError_isReplacedNotLeaked() {
        // 과거(코드화 이전) 저장분의 raw 메시지는 그대로 내보내지 않고 ERROR_1011로 대체한다(read-side 유출 방어).
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.failed(USER_ID, 42L, "Connection refused: 10.0.32.99", "h")));

        DraftTaskStatusResponse res = service.poll("v1", USER_ID, "t");

        assertThat(res.error()).isEqualTo(ErrorCode.ERROR_1011.name());
        assertThat(res.error()).doesNotContain("10.0.32.99");
    }

    @Test
    void poll_success_assemblesTimelineByStoredDailyRecordId() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, 42L, "h")));
        DailyRecord record = DailyRecord.createDraft(USER_ID, DATE, DATE.atTime(12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", 42L);
        when(dailyRecordService.findById(42L)).thenReturn(Optional.of(record));
        DailyTimelineResponse timeline = new DailyTimelineResponse(42L, DATE, null, List.of());
        when(dailyTimelineService.getDailyTimeline(42L)).thenReturn(timeline);

        DraftTaskStatusResponse res = service.poll("v1", USER_ID, "t");

        assertThat(res.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(res.result()).isSameAs(timeline);
        // (userId, recordDate) 재조회로 돌아가지 않는다 — 삭제 후 같은 날짜 재생성 시 오조회 방지.
        verify(dailyRecordService, never()).findByUserIdAndRecordDate(anyLong(), any());
        // 경과 시간은 PROCESSING 전용 — SUCCESS는 null(응답 key 생략)이고 Clock도 읽지 않는다.
        assertThat(res.elapsedSeconds()).isNull();
        verifyNoInteractions(clock);
    }

    @Test
    void poll_success_legacyTaskWithoutRecordId_throws0404() {
        // 배포 전 legacy SUCCESS task(dailyRecordId 부재, terminal TTL 최대 24h 잔존) → 0404(결과 소멸).
        when(timelineTaskService.find("t")).thenReturn(Optional.of(
                new TimelineDraftTask(TaskStatus.SUCCESS, null, null, null, "h", null, USER_ID)));

        assertThatThrownBy(() -> service.poll("v1", USER_ID, "t"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_0404));
        verify(dailyRecordService, never()).findByUserIdAndRecordDate(anyLong(), any());
    }

    @Test
    void poll_success_resultRecordDeleted_throws0404() {
        // 결과 record가 삭제된 SUCCESS task → "task 없음"(1001)과 구분되는 0404(결과 소멸).
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, 42L, "h")));
        when(dailyRecordService.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.poll("v1", USER_ID, "t"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_0404));
    }

    @Test
    void poll_success_resultDeletedAfterOwnershipCheck_stillThrows0404() {
        // polling 선검증 직후 삭제돼 조립 service의 권위 재조회가 miss여도 catch-all 500이 아니라 결과 없음 0404다.
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, 42L, "h")));
        DailyRecord record = DailyRecord.createDraft(USER_ID, DATE, DATE.atTime(12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", 42L);
        when(dailyRecordService.findById(42L)).thenReturn(Optional.of(record));
        when(dailyTimelineService.getDailyTimeline(42L))
                .thenThrow(new BusinessException(ExceptionType.DRAFT_RESULT_NOT_FOUND));

        assertThatThrownBy(() -> service.poll("v1", USER_ID, "t"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.DRAFT_RESULT_NOT_FOUND));
    }

    @Test
    void poll_success_foreignUsersRecord_hiddenAs0404() {
        // 소유권 은닉: task가 남의 record ID를 담고 있어도 존재를 드러내지 않고 0404.
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, 42L, "h")));
        DailyRecord foreign = DailyRecord.createDraft(999L, DATE, DATE.atTime(12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(foreign, "dailyRecordId", 42L);
        when(dailyRecordService.findById(42L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.poll("v1", USER_ID, "t"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_0404));
    }

    @Test
    void poll_notFound_throws404() {
        when(timelineTaskService.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.poll("v1", USER_ID, "missing"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1001));
    }

    @Test
    void poll_foreignUsersTask_hiddenAs1001_beforeAnyDbLookup() {
        // 타 사용자의 task는 상태(SUCCESS 포함)와 무관하게 1001 — DB 조회 전에 끊는다(존재 여부 비노출).
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.success(999L, 42L, "h")));

        assertThatThrownBy(() -> service.poll("v1", USER_ID, "t"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1001));
        verifyNoInteractions(dailyRecordService, dailyTimelineService);
    }

    @Test
    void poll_legacyTaskWithoutOwner_hiddenAs1001() {
        // owner가 없는 배포 전 legacy task는 0으로 추정하지 않고 fail-closed(1001) — 상태별 분기에 못 들어간다.
        when(timelineTaskService.find("t")).thenReturn(Optional.of(new TimelineDraftTask(
                TaskStatus.PROCESSING, 42L, null, null, "h", null, null)));

        assertThatThrownBy(() -> service.poll("v1", USER_ID, "t"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1001));
        verifyNoInteractions(dailyRecordService, dailyTimelineService);
    }
}
