package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 삭제 request 오케스트레이터 단위 검증.
 *
 * <p>request 경로는 S3를 호출하지 않고, 사전 검증 → PHOTO job enqueue를 포함한 DB transaction 순서만
 * 소유한다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineDeletionServiceTest {

    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineDeletionTransactionService timelineDeletionTransactionService;

    private static final String VERSION = "v1";
    private static final UUID SUBJECT_ID = id(7L);
    private static final UUID OTHER_SUBJECT_ID = id(999L);
    private static final Long EVENT_ID = 11L;
    private static final Long RECORD_ID = 100L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 7, 8);

    private TimelineDeletionService service;

    @BeforeEach
    void setUp() {
        service = new TimelineDeletionService(
                timelineEventService,
                dailyRecordService,
                timelineDeletionTransactionService);
    }

    @Test
    void deleteEvent_runsTransaction() {
        stubOwnedDraftEvent(new TimelineDeletionTransactionService.DeletionResult(2, 1, 3));

        service.deleteEvent(VERSION, SUBJECT_ID, EVENT_ID);

        verify(timelineDeletionTransactionService).deleteEvent(SUBJECT_ID, EVENT_ID);
    }

    @Test
    void deleteDailyRecord_runsTransaction() {
        stubOwnedDraftRecord(new TimelineDeletionTransactionService.DeletionResult(4, 2, 1));

        service.deleteDailyRecord(VERSION, SUBJECT_ID, RECORD_ID);

        verify(timelineDeletionTransactionService).deleteDailyRecord(SUBJECT_ID, RECORD_ID);
    }

    @Test
    void deleteDailyRecordByDate_resolvesSnapshotIdAndRunsTransaction() {
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
        when(timelineDeletionTransactionService.deleteDailyRecord(SUBJECT_ID, RECORD_ID))
                .thenReturn(new TimelineDeletionTransactionService.DeletionResult(3, 2, 1));

        service.deleteDailyRecordByDate(VERSION, SUBJECT_ID, RECORD_DATE);

        verify(dailyRecordService).findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE);
        verify(timelineDeletionTransactionService).deleteDailyRecord(SUBJECT_ID, RECORD_ID);
    }

    @Test
    void detachEventItem_runsTransaction() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
        when(timelineDeletionTransactionService.detachEventItem(SUBJECT_ID, EVENT_ID, 21L))
                .thenReturn(new TimelineDeletionTransactionService.DeletionResult(1, 0, 0));

        service.detachEventItem(VERSION, SUBJECT_ID, EVENT_ID, 21L);

        verify(timelineDeletionTransactionService).detachEventItem(SUBJECT_ID, EVENT_ID, 21L);
    }

    @Test
    void detachEventItem_hidesUnknownEventBeforeTransaction() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detachEventItem(VERSION, SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(timelineDeletionTransactionService);
    }

    @Test
    void detachEventItem_hidesForeignRecordBeforeTransaction() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(OTHER_SUBJECT_ID)));

        assertThatThrownBy(() -> service.detachEventItem(VERSION, SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(timelineDeletionTransactionService);
    }

    @Test
    void detachEventItem_rejectsSavedRecordBeforeTransaction() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        DailyRecord saved = draftRecordOf(SUBJECT_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.detachEventItem(VERSION, SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1003);
                });

        verifyNoInteractions(timelineDeletionTransactionService);
    }

    @Test
    void detachEventItem_dbFailurePropagates() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
        when(timelineDeletionTransactionService.detachEventItem(SUBJECT_ID, EVENT_ID, 21L))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.detachEventItem(VERSION, SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

    }

    @Test
    void deleteEvent_dbFailurePropagates() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
        when(timelineDeletionTransactionService.deleteEvent(SUBJECT_ID, EVENT_ID))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.deleteEvent(VERSION, SUBJECT_ID, EVENT_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

    }

    @Test
    void deleteDailyRecord_dbFailurePropagates() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
        when(timelineDeletionTransactionService.deleteDailyRecord(SUBJECT_ID, RECORD_ID))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, SUBJECT_ID, RECORD_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

    }

    @Test
    void deleteDailyRecordByDate_dbFailurePropagates() {
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
        when(timelineDeletionTransactionService.deleteDailyRecord(SUBJECT_ID, RECORD_ID))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.deleteDailyRecordByDate(VERSION, SUBJECT_ID, RECORD_DATE))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

    }

    @Test
    void deleteEvent_hidesUnknownEventBeforeTransaction() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteEvent(VERSION, SUBJECT_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(timelineDeletionTransactionService);
    }

    @Test
    void deleteEvent_hidesForeignRecordBeforeTransaction() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(OTHER_SUBJECT_ID)));

        assertThatThrownBy(() -> service.deleteEvent(VERSION, SUBJECT_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(timelineDeletionTransactionService);
    }

    @Test
    void deleteEvent_rejectsSavedRecordBeforeTransaction() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        DailyRecord saved = draftRecordOf(SUBJECT_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.deleteEvent(VERSION, SUBJECT_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1003);
                });

        verifyNoInteractions(timelineDeletionTransactionService);
    }

    @Test
    void deleteDailyRecord_hidesUnknownRecordBeforeTransaction() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, SUBJECT_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(timelineDeletionTransactionService);
    }

    @Test
    void deleteDailyRecord_hidesForeignRecordBeforeTransaction() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(OTHER_SUBJECT_ID)));

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, SUBJECT_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(timelineDeletionTransactionService);
    }

    @Test
    void deleteDailyRecord_rejectsSavedRecordBeforeTransaction() {
        DailyRecord saved = draftRecordOf(SUBJECT_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, SUBJECT_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1003);
                });

        verifyNoInteractions(timelineDeletionTransactionService);
    }

    @Test
    void deleteDailyRecordByDate_hidesUnknownRecordBeforeTransaction() {
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDailyRecordByDate(VERSION, SUBJECT_ID, RECORD_DATE))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(timelineDeletionTransactionService);
    }

    @Test
    void deleteDailyRecordByDate_rejectsSavedRecordBeforeTransaction() {
        DailyRecord saved = draftRecordOf(SUBJECT_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.deleteDailyRecordByDate(VERSION, SUBJECT_ID, RECORD_DATE))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1003);
                });

        verifyNoInteractions(timelineDeletionTransactionService);
    }

    private void stubOwnedDraftEvent(TimelineDeletionTransactionService.DeletionResult result) {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
        when(timelineDeletionTransactionService.deleteEvent(SUBJECT_ID, EVENT_ID)).thenReturn(result);
    }

    private void stubOwnedDraftRecord(TimelineDeletionTransactionService.DeletionResult result) {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
        when(timelineDeletionTransactionService.deleteDailyRecord(SUBJECT_ID, RECORD_ID)).thenReturn(result);
    }

    private TimelineEvent event() {
        TimelineEvent event = TimelineEvent.of(
                RECORD_ID,
                TimelineEventType.UNKNOWN,
                RECORD_DATE.atTime(9, 0),
                null,
                "제목",
                null, null);
        ReflectionTestUtils.setField(event, "timelineEventId", EVENT_ID);
        return event;
    }

    private DailyRecord draftRecordOf(UUID subjectId) {
        DailyRecord record = DailyRecord.createDraft(
                subjectId,
                RECORD_DATE,
                RECORD_DATE.atTime(12, 0),
                "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }
}
