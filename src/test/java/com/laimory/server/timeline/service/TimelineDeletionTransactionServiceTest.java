package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 삭제 DB 트랜잭션 bean 단위 검증: S3 삭제 동안 상태가 변했을 수 있으므로 트랜잭션 안에서 소유권·DRAFT를
 * 재확인한 뒤에만 deleteById를 호출한다(재확인 실패는 사전 검증과 같은 404/1003). 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class TimelineDeletionTransactionServiceTest {

    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private DailyRecordService dailyRecordService;

    @InjectMocks
    private TimelineDeletionTransactionService service;

    private static final long USER_ID = 7L;
    private static final Long EVENT_ID = 11L;
    private static final Long RECORD_ID = 100L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 7, 8);

    private TimelineEvent event() {
        TimelineEvent event = TimelineEvent.of(RECORD_ID, RECORD_DATE.atTime(9, 0), null, "제목", null);
        ReflectionTestUtils.setField(event, "timelineEventId", EVENT_ID);
        return event;
    }

    private DailyRecord draftRecordOf(long userId) {
        DailyRecord record = DailyRecord.createDraft(userId, RECORD_DATE, RECORD_DATE.atTime(12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }

    @Test
    void deleteEvent_recheckPassesAndDeletesEventRow() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));

        service.deleteEvent(USER_ID, EVENT_ID);

        verify(timelineEventService).deleteById(EVENT_ID);
        verify(dailyRecordService, never()).deleteById(anyLong());
    }

    @Test
    void deleteEvent_missingEventOnRecheckIs404() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteEvent(USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        verify(timelineEventService, never()).deleteById(anyLong());
    }

    @Test
    void deleteEvent_foreignRecordOnRecheckIs404() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(999L)));

        assertThatThrownBy(() -> service.deleteEvent(USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_0404));
        verify(timelineEventService, never()).deleteById(anyLong());
    }

    @Test
    void deleteEvent_savedRecordOnRecheckIs1003() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        DailyRecord saved = draftRecordOf(USER_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.deleteEvent(USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1003));
        verify(timelineEventService, never()).deleteById(anyLong());
    }

    @Test
    void deleteDailyRecord_recheckPassesAndDeletesRecordRow() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));

        service.deleteDailyRecord(USER_ID, RECORD_ID);

        verify(dailyRecordService).deleteById(RECORD_ID);
    }

    @Test
    void deleteDailyRecord_missingRecordOnRecheckIs404() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDailyRecord(USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND));
        verify(dailyRecordService, never()).deleteById(anyLong());
    }

    @Test
    void deleteDailyRecord_savedRecordOnRecheckIs1003() {
        DailyRecord saved = draftRecordOf(USER_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.deleteDailyRecord(USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1003));
        verify(dailyRecordService, never()).deleteById(anyLong());
    }
}
