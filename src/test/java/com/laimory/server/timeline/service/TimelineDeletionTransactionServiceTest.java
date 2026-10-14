package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 삭제 DB transaction 단위 검증.
 *
 * <p>Event·DailyRecord 삭제는 소유권을 재확인한 뒤 soft delete만 한다 — junction·Item은 건드리지 않는다(#568).
 * 연결 해제는 junction 한 줄만 지우고 Item은 남긴다 — 마지막 참조였어도 Item·S3 정리는 orphan 스위퍼의 몫이다(#551).
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.util.UUID SUBJECT_ID = com.laimory.server.testsupport.TestSubjects.id(7L);
    private static final java.util.UUID OTHER_SUBJECT_ID =
            com.laimory.server.testsupport.TestSubjects.id(999L);
    private static final Long EVENT_ID = 11L;
    private static final Long RECORD_ID = 100L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 7, 8);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 12, 0);

    private TimelineDeletionTransactionService service;

    @BeforeEach
    void setUp() {
        service = new TimelineDeletionTransactionService(
                timelineEventService,
                timelineEventItemService,
                timelineItemService,
                dailyRecordService,
                Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneId.of("Asia/Seoul")));
    }

    private TimelineEvent event(long eventId) {
        TimelineEvent event = TimelineEvent.of(
                RECORD_ID,
                TimelineEventType.UNKNOWN,
                RECORD_DATE.atTime(9, 0),
                null,
                "제목",
                null, null, null, null);
        ReflectionTestUtils.setField(event, "timelineEventId", eventId);
        return event;
    }

    private DailyRecord draftRecordOf(java.util.UUID subjectId) {
        DailyRecord record = DailyRecord.createDraft(
                subjectId,
                RECORD_DATE,
                RECORD_DATE.atTime(12, 0),
                "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }

    private TimelineItem photoItem(long itemId, String filename) {
        TimelineItem item = TimelineItem.of(
                ItemType.PHOTO,
                "raw-" + itemId,
                RECORD_DATE.atTime(9, 5),
                null,
                MAPPER.valueToTree(new PhotoPayload(
                        filename,
                        "content://x",
                        1.0,
                        2.0,
                        null,
                        null, null,
                        "https://cdn.example/" + filename)));
        ReflectionTestUtils.setField(item, "timelineItemId", itemId);
        return item;
    }

    private TimelineItem calendarItem(long itemId) {
        TimelineItem item = TimelineItem.of(
                ItemType.CALENDAR,
                "raw-" + itemId,
                RECORD_DATE.atTime(10, 0),
                null,
                MAPPER.valueToTree(new CalendarPayload("회의", null, null, false)));
        ReflectionTestUtils.setField(item, "timelineItemId", itemId);
        return item;
    }

    private void stubOwnedDraftEvent() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID)));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
    }

    @Test
    void deleteEventSoftDeletesEventAndLeavesItems() {
        stubOwnedDraftEvent();

        service.deleteEvent(SUBJECT_ID, EVENT_ID);

        verify(timelineEventService).softDelete(EVENT_ID, NOW);
        verifyNoInteractions(timelineEventItemService, timelineItemService);
    }

    @Test
    void deleteDailyRecordSoftDeletesRecordAndItsEventsAtSameTime() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
        when(dailyRecordService.softDelete(RECORD_ID, NOW)).thenReturn(1);

        service.deleteDailyRecord(SUBJECT_ID, RECORD_ID);

        verify(timelineEventService).softDeleteByDailyRecordId(RECORD_ID, NOW);
        verifyNoInteractions(timelineEventItemService, timelineItemService);
    }

    @Test
    void deleteEvent_missingEventOnRecheckIs404WithoutMutation() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteEvent(SUBJECT_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(timelineEventService, never()).softDelete(anyLong(), any());
        verify(timelineItemService, never()).deleteByIds(anyCollection());
    }

    @Test
    void deleteEvent_foreignRecordOnRecheckIs404WithoutMutation() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID)));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(OTHER_SUBJECT_ID)));

        assertThatThrownBy(() -> service.deleteEvent(SUBJECT_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));
        verify(timelineEventService, never()).softDelete(anyLong(), any());
    }

    @Test
    void deleteEvent_savedRecordOnRecheckIsStillDeletable() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID)));
        DailyRecord saved = draftRecordOf(SUBJECT_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        service.deleteEvent(SUBJECT_ID, EVENT_ID);

        verify(timelineEventService).softDelete(EVENT_ID, NOW);
    }

    @Test
    void detachEventItem_lastReferenceRemovesJunctionAndLeavesItemForSweeper() {
        stubOwnedDraftEvent();
        when(timelineItemService.findById(21L)).thenReturn(Optional.of(photoItem(21L, "last.jpg")));
        when(timelineEventItemService.isLinked(EVENT_ID, 21L)).thenReturn(true);
        when(timelineEventItemService.deleteLink(EVENT_ID, 21L)).thenReturn(1);

        service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L);

        verify(timelineEventItemService).deleteLink(EVENT_ID, 21L);
        verify(timelineItemService, never()).deleteByIds(anyCollection());
    }

    @Test
    void detachEventItem_concurrentlyRemovedJunctionIs404WithoutOrphanHandling() {
        stubOwnedDraftEvent();
        // 스냅숏 존재 확인은 통과했지만 직접 DELETE가 0행 — 같은 junction 동시 해제의 후발 요청.
        when(timelineItemService.findById(21L)).thenReturn(Optional.of(photoItem(21L, "race.jpg")));
        when(timelineEventItemService.isLinked(EVENT_ID, 21L)).thenReturn(true);
        when(timelineEventItemService.deleteLink(EVENT_ID, 21L)).thenReturn(0);

        assertThatThrownBy(() -> service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(timelineItemService, never()).deleteByIds(anyCollection());
    }

    @Test
    void detachEventItem_nonPhotoItemIsRejectedWithoutMutation() {
        stubOwnedDraftEvent();
        when(timelineItemService.findById(21L)).thenReturn(Optional.of(calendarItem(21L)));
        when(timelineEventItemService.isLinked(EVENT_ID, 21L)).thenReturn(true);

        assertThatThrownBy(() -> service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-1018));

        verify(timelineEventItemService, never()).deleteLink(anyLong(), anyLong());
        verify(timelineItemService, never()).deleteByIds(anyCollection());
    }

    @Test
    void detachEventItem_unlinkedItemIs404BeforeTypeCheckWithoutMutation() {
        stubOwnedDraftEvent();
        // 미연결 non-PHOTO도 -1018이 아니라 404 은닉이 우선이다.
        when(timelineItemService.findById(21L)).thenReturn(Optional.of(calendarItem(21L)));
        when(timelineEventItemService.isLinked(EVENT_ID, 21L)).thenReturn(false);

        assertThatThrownBy(() -> service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(timelineEventItemService, never()).deleteLink(anyLong(), anyLong());
    }

    @Test
    void detachEventItem_missingItemIs404WithoutMutation() {
        stubOwnedDraftEvent();
        when(timelineItemService.findById(21L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(timelineEventItemService, never()).deleteLink(anyLong(), anyLong());
    }

    @Test
    void detachEventItem_missingEventIs404WithoutMutation() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(timelineEventItemService, never()).deleteLink(anyLong(), anyLong());
    }

    @Test
    void detachEventItem_foreignRecordIs404WithoutMutation() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID)));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(OTHER_SUBJECT_ID)));

        assertThatThrownBy(() -> service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(timelineEventItemService, never()).deleteLink(anyLong(), anyLong());
    }

    @Test
    void detachEventItem_savedRecordIsStillDetachable() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID)));
        DailyRecord saved = draftRecordOf(SUBJECT_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));
        when(timelineItemService.findById(21L)).thenReturn(Optional.of(photoItem(21L, "saved.jpg")));
        when(timelineEventItemService.isLinked(EVENT_ID, 21L)).thenReturn(true);
        when(timelineEventItemService.deleteLink(EVENT_ID, 21L)).thenReturn(1);

        service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L);

        verify(timelineEventItemService).deleteLink(EVENT_ID, 21L);
    }

    @Test
    void deleteDailyRecord_missingRecordOnRecheckIs404WithoutMutation() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDailyRecord(SUBJECT_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(dailyRecordService, never()).softDelete(anyLong(), any());
        verify(timelineItemService, never()).deleteByIds(anyCollection());
    }

    @Test
    void deleteDailyRecord_savedRecordOnRecheckIsStillDeletable() {
        DailyRecord saved = draftRecordOf(SUBJECT_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        service.deleteDailyRecord(SUBJECT_ID, RECORD_ID);

        verify(dailyRecordService).softDelete(RECORD_ID, NOW);
    }
}
