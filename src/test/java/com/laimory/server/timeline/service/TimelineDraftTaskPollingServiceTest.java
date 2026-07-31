package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import static com.laimory.server.testsupport.TaskTokenFixtures.tokenHashes;

import com.laimory.server.common.error.BusinessException;
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
        return TimelineDraftTask.processing(USER_ID, 42L, null, tokenHashes("hash"), processingStartedAt);
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
    void poll_failed_returnsFailureCode() {
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.failed(USER_ID, 42L, -1009, tokenHashes("h"))));

        DraftTaskStatusResponse res = service.poll("v1", USER_ID, "t");

        assertThat(res.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(res.error()).isEqualTo(-1009); // body.error = 실패 분류 numeric code
        assertThat(res.result()).isNull();
        // 경과 시간은 PROCESSING 전용 — terminal은 null(응답 key 생략)이고 Clock도 읽지 않는다.
        assertThat(res.elapsedSeconds()).isNull();
        verifyNoInteractions(clock);
    }

    @Test
    void poll_failed_unknownNumericError_isReplacedNotLeaked() {
        // 오염되거나 미지인 numeric code는 그대로 내보내지 않고 -1011로 대체한다(read-side 유출 방어).
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.failed(USER_ID, 42L, 1234, tokenHashes("h"))));

        DraftTaskStatusResponse res = service.poll("v1", USER_ID, "t");

        assertThat(res.error()).isEqualTo(-1011);
    }

    @Test
    void poll_success_assemblesTimelineByStoredDailyRecordId() {
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, 42L, tokenHashes("h"))));
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
    void poll_success_resultRecordDeleted_throws0404() {
        // 결과 record가 삭제된 SUCCESS task → "task 없음"(1001)과 구분되는 0404(결과 소멸).
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, 42L, tokenHashes("h"))));
        when(dailyRecordService.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.poll("v1", USER_ID, "t"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-404));
    }

    @Test
    void poll_success_resultDeletedAfterOwnershipCheck_stillThrows0404() {
        // polling 선검증 직후 삭제돼 조립 service의 권위 재조회가 miss여도 catch-all 500이 아니라 결과 없음 0404다.
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, 42L, tokenHashes("h"))));
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
        when(timelineTaskService.find("t")).thenReturn(Optional.of(TimelineDraftTask.success(USER_ID, 42L, tokenHashes("h"))));
        DailyRecord foreign = DailyRecord.createDraft(999L, DATE, DATE.atTime(12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(foreign, "dailyRecordId", 42L);
        when(dailyRecordService.findById(42L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.poll("v1", USER_ID, "t"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-404));
    }

    @Test
    void poll_notFound_throws404() {
        when(timelineTaskService.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.poll("v1", USER_ID, "missing"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1001));
    }

    @Test
    void poll_foreignUsersTask_hiddenAs1001_beforeAnyDbLookup() {
        // 타 사용자의 task는 상태(SUCCESS 포함)와 무관하게 1001 — DB 조회 전에 끊는다(존재 여부 비노출).
        when(timelineTaskService.find("t"))
                .thenReturn(Optional.of(TimelineDraftTask.success(999L, 42L, tokenHashes("h"))));

        assertThatThrownBy(() -> service.poll("v1", USER_ID, "t"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1001));
        verifyNoInteractions(dailyRecordService, dailyTimelineService);
    }

}
