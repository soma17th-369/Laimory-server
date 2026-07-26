package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 삭제 request 오케스트레이터 단위 검증.
 *
 * <p>request 경로는 S3를 호출하지 않고, 사전 검증 → 날짜 guard → PHOTO job enqueue를 포함한 DB transaction
 * → commit 결과 metric → guard 해제 순서만 소유한다. transaction 실패에는 enqueue metric을 기록하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineDeletionServiceTest {

    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineDeletionTransactionService timelineDeletionTransactionService;
    @Mock
    private TimelinePhotoDeleteMetrics timelinePhotoDeleteMetrics;

    private static final String VERSION = "v1";
    private static final long USER_ID = 7L;
    private static final Long EVENT_ID = 11L;
    private static final Long RECORD_ID = 100L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 7, 8);

    private TimelineDeletionService service;

    @BeforeEach
    void setUp() {
        service = new TimelineDeletionService(
                timelineEventService,
                dailyRecordService,
                timelineTaskService,
                timelineDeletionTransactionService,
                timelinePhotoDeleteMetrics);
    }

    private TimelineEvent event() {
        TimelineEvent event = TimelineEvent.of(
                RECORD_ID,
                TimelineEventType.UNKNOWN,
                RECORD_DATE.atTime(9, 0),
                null,
                "제목",
                null);
        ReflectionTestUtils.setField(event, "timelineEventId", EVENT_ID);
        return event;
    }

    private DailyRecord draftRecordOf(long userId) {
        DailyRecord record = DailyRecord.createDraft(
                userId,
                RECORD_DATE,
                RECORD_DATE.atTime(12, 0),
                "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }

    private void stubOwnedDraftEventWithGuard(
            TimelineDeletionTransactionService.DeletionResult result) {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString()))
                .thenReturn(true);
        when(timelineDeletionTransactionService.deleteEvent(USER_ID, EVENT_ID)).thenReturn(result);
    }

    private void stubOwnedDraftRecordWithGuard(
            TimelineDeletionTransactionService.DeletionResult result) {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString()))
                .thenReturn(true);
        when(timelineDeletionTransactionService.deleteDailyRecord(USER_ID, RECORD_ID)).thenReturn(result);
    }

    @Test
    void deleteEvent_guardWrapsTransactionAndCommittedResultRecordsEnqueueMetrics() {
        stubOwnedDraftEventWithGuard(
                new TimelineDeletionTransactionService.DeletionResult(2, 1, 3));

        service.deleteEvent(VERSION, USER_ID, EVENT_ID);

        InOrder order = inOrder(
                timelineTaskService,
                timelineDeletionTransactionService,
                timelinePhotoDeleteMetrics);
        order.verify(timelineTaskService)
                .claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
        order.verify(timelineDeletionTransactionService).deleteEvent(USER_ID, EVENT_ID);
        order.verify(timelinePhotoDeleteMetrics).recordEnqueueScheduled(2);
        order.verify(timelinePhotoDeleteMetrics).recordEnqueueSharedRetained(1);
        order.verify(timelinePhotoDeleteMetrics).recordEnqueueInvalidSkipped(3);
        order.verify(timelineTaskService)
                .releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
    }

    @Test
    void deleteDailyRecord_guardWrapsTransactionAndCommittedResultRecordsEnqueueMetrics() {
        stubOwnedDraftRecordWithGuard(
                new TimelineDeletionTransactionService.DeletionResult(4, 2, 1));

        service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID);

        InOrder order = inOrder(
                timelineTaskService,
                timelineDeletionTransactionService,
                timelinePhotoDeleteMetrics);
        order.verify(timelineTaskService)
                .claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
        order.verify(timelineDeletionTransactionService).deleteDailyRecord(USER_ID, RECORD_ID);
        order.verify(timelinePhotoDeleteMetrics).recordEnqueueScheduled(4);
        order.verify(timelinePhotoDeleteMetrics).recordEnqueueSharedRetained(2);
        order.verify(timelinePhotoDeleteMetrics).recordEnqueueInvalidSkipped(1);
        order.verify(timelineTaskService)
                .releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
    }

    @Test
    void deleteEvent_claimsAndReleasesSameDeleteHolder() {
        stubOwnedDraftEventWithGuard(
                new TimelineDeletionTransactionService.DeletionResult(0, 0, 0));

        service.deleteEvent(VERSION, USER_ID, EVENT_ID);

        ArgumentCaptor<String> claimed = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> released = ArgumentCaptor.forClass(String.class);
        verify(timelineTaskService)
                .claimDateGuard(eq(USER_ID), eq(RECORD_DATE), claimed.capture());
        verify(timelineTaskService)
                .releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), released.capture());
        assertThat(claimed.getValue()).startsWith("delete:");
        assertThat(released.getValue()).isEqualTo(claimed.getValue());
    }

    @Test
    void deleteEvent_dbFailurePropagatesReleasesGuardAndDoesNotRecordMetrics() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString()))
                .thenReturn(true);
        when(timelineDeletionTransactionService.deleteEvent(USER_ID, EVENT_ID))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(timelineTaskService)
                .releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
        verifyNoInteractions(timelinePhotoDeleteMetrics);
    }

    @Test
    void deleteDailyRecord_dbFailurePropagatesReleasesGuardAndDoesNotRecordMetrics() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString()))
                .thenReturn(true);
        when(timelineDeletionTransactionService.deleteDailyRecord(USER_ID, RECORD_ID))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        verify(timelineTaskService)
                .releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
        verifyNoInteractions(timelinePhotoDeleteMetrics);
    }

    @Test
    void deleteEvent_releaseFailureIsSwallowedAfterCommittedDelete() {
        stubOwnedDraftEventWithGuard(
                new TimelineDeletionTransactionService.DeletionResult(1, 0, 0));
        when(timelineTaskService.releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .doesNotThrowAnyException();

        verify(timelineDeletionTransactionService).deleteEvent(USER_ID, EVENT_ID);
        verify(timelinePhotoDeleteMetrics).recordEnqueueScheduled(1);
    }

    @Test
    void deleteEvent_hidesUnknownEventBeforeGuardAndTransaction() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(
                timelineTaskService,
                timelineDeletionTransactionService,
                timelinePhotoDeleteMetrics);
    }

    @Test
    void deleteEvent_hidesForeignRecordBeforeGuardAndTransaction() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(999L)));

        assertThatThrownBy(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(
                timelineTaskService,
                timelineDeletionTransactionService,
                timelinePhotoDeleteMetrics);
    }

    @Test
    void deleteEvent_rejectsSavedRecordBeforeGuardAndTransaction() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        DailyRecord saved = draftRecordOf(USER_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1003);
                });

        verifyNoInteractions(
                timelineTaskService,
                timelineDeletionTransactionService,
                timelinePhotoDeleteMetrics);
    }

    @Test
    void deleteEvent_guardClaimFailureDoesNotRunTransactionOrReleaseAnotherHolder() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.RECORD_DATE_IN_PROGRESS);
                    assertThat(exception.getErrorCode()).isEqualTo(-1016);
                });

        verifyNoInteractions(timelineDeletionTransactionService, timelinePhotoDeleteMetrics);
        verify(timelineTaskService, never())
                .releaseDateGuard(anyLong(), any(), anyString());
    }

    @Test
    void deleteDailyRecord_hidesUnknownRecordBeforeGuardAndTransaction() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(
                timelineTaskService,
                timelineDeletionTransactionService,
                timelinePhotoDeleteMetrics);
    }

    @Test
    void deleteDailyRecord_hidesForeignRecordBeforeGuardAndTransaction() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(999L)));

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(
                timelineTaskService,
                timelineDeletionTransactionService,
                timelinePhotoDeleteMetrics);
    }

    @Test
    void deleteDailyRecord_rejectsSavedRecordBeforeGuardAndTransaction() {
        DailyRecord saved = draftRecordOf(USER_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1003);
                });

        verifyNoInteractions(
                timelineTaskService,
                timelineDeletionTransactionService,
                timelinePhotoDeleteMetrics);
    }

    @Test
    void deleteDailyRecord_guardClaimFailureDoesNotRunTransactionOrReleaseAnotherHolder() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.RECORD_DATE_IN_PROGRESS);
                    assertThat(exception.getErrorCode()).isEqualTo(-1016);
                });

        verifyNoInteractions(timelineDeletionTransactionService, timelinePhotoDeleteMetrics);
        verify(timelineTaskService, never())
                .releaseDateGuard(anyLong(), any(), anyString());
    }
}
