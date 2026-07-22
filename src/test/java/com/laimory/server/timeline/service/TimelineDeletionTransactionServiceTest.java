package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 삭제 DB 트랜잭션 bean 단위 검증: S3 삭제 동안 상태가 변했을 수 있으므로 트랜잭션 안에서 소유권·DRAFT를
 * 재확인한 뒤에만 삭제하고(재확인 실패는 사전 검증과 같은 404/1003), Item은 record FK cascade가 없으므로
 * 삭제 대상 Event에만 연결된 orphan을 명시적으로 지운다(shared는 유지). 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class TimelineDeletionTransactionServiceTest {

    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private TimelineEventItemService timelineEventItemService;
    @Mock
    private TimelineItemService timelineItemService;
    @Mock
    private DailyRecordService dailyRecordService;

    @InjectMocks
    private TimelineDeletionTransactionService service;

    private static final long USER_ID = 7L;
    private static final Long EVENT_ID = 11L;
    private static final Long RECORD_ID = 100L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 7, 8);

    private TimelineEvent event() {
        TimelineEvent event = TimelineEvent.of(RECORD_ID, TimelineEventType.UNKNOWN, RECORD_DATE.atTime(9, 0), null, "제목", null);
        ReflectionTestUtils.setField(event, "timelineEventId", EVENT_ID);
        return event;
    }

    private DailyRecord draftRecordOf(long userId) {
        DailyRecord record = DailyRecord.createDraft(userId, RECORD_DATE, RECORD_DATE.atTime(12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }

    @Test
    void deleteEvent_recheckPasses_deletesEventRowAndOrphanItemsOnly() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        // 후보 21·22 중 21은 이 event에만(orphan 예정), 22는 다른 event(12)에도 연결(shared — 유지).
        when(timelineEventItemService.findByTimelineEventIds(anyCollection()))
                .thenReturn(List.of(TimelineEventItem.of(EVENT_ID, 21L), TimelineEventItem.of(EVENT_ID, 22L)));
        when(timelineEventItemService.findByTimelineItemIds(anyCollection()))
                .thenReturn(List.of(TimelineEventItem.of(EVENT_ID, 21L),
                        TimelineEventItem.of(EVENT_ID, 22L), TimelineEventItem.of(12L, 22L)));

        service.deleteEvent(USER_ID, EVENT_ID);

        verify(timelineEventService).deleteById(EVENT_ID);
        ArgumentCaptor<Collection<Long>> orphanCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(timelineItemService).deleteByIds(orphanCaptor.capture());
        assertThat(orphanCaptor.getValue()).containsExactly(21L);
        verify(dailyRecordService, never()).deleteById(anyLong());
        // orphan 판정은 삭제 전 junction 스냅샷 기준이다 — 조회가 삭제보다 앞선다(stale 읽기 방지).
        InOrder order = inOrder(timelineEventItemService, timelineEventService);
        order.verify(timelineEventItemService).findByTimelineItemIds(anyCollection());
        order.verify(timelineEventService).deleteById(EVENT_ID);
    }

    @Test
    void deleteEvent_missingEventOnRecheckIs404() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteEvent(USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        verify(timelineEventService, never()).deleteById(anyLong());
        verify(timelineItemService, never()).deleteByIds(anyCollection());
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
    void deleteDailyRecord_recheckPasses_deletesRecordRowAndOrphanItems() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineEventService.findByDailyRecordId(RECORD_ID)).thenReturn(List.of(event()));
        // 후보 21은 record 안 event에만(orphan 예정), 22는 record 밖 event(99)에도 연결(shared — 유지).
        when(timelineEventItemService.findByTimelineEventIds(anyCollection()))
                .thenReturn(List.of(TimelineEventItem.of(EVENT_ID, 21L), TimelineEventItem.of(EVENT_ID, 22L)));
        when(timelineEventItemService.findByTimelineItemIds(anyCollection()))
                .thenReturn(List.of(TimelineEventItem.of(EVENT_ID, 21L),
                        TimelineEventItem.of(EVENT_ID, 22L), TimelineEventItem.of(99L, 22L)));

        service.deleteDailyRecord(USER_ID, RECORD_ID);

        verify(dailyRecordService).deleteById(RECORD_ID);
        ArgumentCaptor<Collection<Long>> orphanCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(timelineItemService).deleteByIds(orphanCaptor.capture());
        assertThat(orphanCaptor.getValue()).containsExactly(21L);
    }

    @Test
    void deleteDailyRecord_missingRecordOnRecheckIs404() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDailyRecord(USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND));
        verify(dailyRecordService, never()).deleteById(anyLong());
        verify(timelineItemService, never()).deleteByIds(anyCollection());
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
