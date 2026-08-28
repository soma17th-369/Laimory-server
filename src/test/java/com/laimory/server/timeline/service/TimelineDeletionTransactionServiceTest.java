package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 삭제 DB transaction 단위 검증.
 *
 * <p>소유권·DRAFT를 재확인하고 마지막 참조가 사라지는 PHOTO의 delete job을 root hard delete보다 먼저
 * enqueue한다. shared PHOTO는 유지하고, 손상 payload는 job만 생략하며 commit 이후 로그에 남길 결과
 * 건수를 반환한다.
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
    @Mock
    private TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;
    @Mock
    private com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore userMemoryUpdatePendingStore;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final java.util.UUID SUBJECT_ID = com.laimory.server.testsupport.TestSubjects.id(7L);
    private static final java.util.UUID OTHER_SUBJECT_ID =
            com.laimory.server.testsupport.TestSubjects.id(999L);
    private static final Long EVENT_ID = 11L;
    private static final Long RECORD_ID = 100L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 7, 8);

    private TimelineDeletionTransactionService service;

    @BeforeEach
    void setUp() {
        service = new TimelineDeletionTransactionService(
                timelineEventService,
                timelineEventItemService,
                timelineItemService,
                dailyRecordService,
                timelinePhotoDeleteJobService,
                userMemoryUpdatePendingStore,
                MAPPER);
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

    private TimelineItem brokenPhotoItem(long itemId) {
        TimelineItem item = TimelineItem.of(
                ItemType.PHOTO,
                "raw-" + itemId,
                RECORD_DATE.atTime(11, 0),
                null,
                MAPPER.createArrayNode());
        ReflectionTestUtils.setField(item, "timelineItemId", itemId);
        return item;
    }

    private TimelineItem nullPhotoItem(long itemId) {
        TimelineItem item = TimelineItem.of(
                ItemType.PHOTO,
                "raw-" + itemId,
                RECORD_DATE.atTime(11, 0),
                null,
                MAPPER.nullNode());
        ReflectionTestUtils.setField(item, "timelineItemId", itemId);
        return item;
    }

    private void stubOwnedDraftEvent() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID)));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
    }

    @Test
    void deleteEvent_enqueuesOrphanPhotoBeforeHardDeleteAndReturnsCounts() {
        stubOwnedDraftEvent();
        TimelineItem orphanPhoto = photoItem(21L, "a.jpg");
        TimelineItem orphanCalendar = calendarItem(22L);
        TimelineItem sharedPhoto = photoItem(23L, "shared.jpg");
        when(timelineEventItemService.findByTimelineEventIds(anyCollection()))
                .thenReturn(List.of(
                        TimelineEventItem.of(EVENT_ID, 21L),
                        TimelineEventItem.of(EVENT_ID, 22L),
                        TimelineEventItem.of(EVENT_ID, 23L)));
        when(timelineEventItemService.findByTimelineItemIds(anyCollection()))
                .thenReturn(List.of(
                        TimelineEventItem.of(EVENT_ID, 21L),
                        TimelineEventItem.of(EVENT_ID, 22L),
                        TimelineEventItem.of(EVENT_ID, 23L),
                        TimelineEventItem.of(12L, 23L)));
        when(timelineItemService.findByIds(anyCollection()))
                .thenReturn(List.of(orphanPhoto, orphanCalendar, sharedPhoto));
        String objectKey = PhotoObjectKeys.subjectFullKey("a.jpg", SUBJECT_ID);
        when(timelinePhotoDeleteJobService.insertIfAbsent(21L, objectKey)).thenReturn(true);

        TimelineDeletionTransactionService.DeletionResult result =
                service.deleteEvent(SUBJECT_ID, EVENT_ID);

        assertThat(result).isEqualTo(
                new TimelineDeletionTransactionService.DeletionResult(1, 1, 0));
        verify(timelinePhotoDeleteJobService, never())
                .insertIfAbsent(eq(23L), anyString());
        ArgumentCaptor<Collection<Long>> immediateDeleteIds =
                ArgumentCaptor.forClass(Collection.class);
        verify(timelineItemService).deleteByIds(immediateDeleteIds.capture());
        assertThat(immediateDeleteIds.getValue()).containsExactly(22L);

        InOrder order = inOrder(
                timelinePhotoDeleteJobService,
                timelineEventService,
                timelineItemService);
        order.verify(timelinePhotoDeleteJobService).insertIfAbsent(21L, objectKey);
        order.verify(timelineEventService).deleteById(EVENT_ID);
        order.verify(timelineItemService).deleteByIds(anyCollection());
        verify(dailyRecordService, never()).deleteById(anyLong());
    }

    @Test
    void deleteEvent_sharedPhotoIsRetainedWithoutDeleteJob() {
        stubOwnedDraftEvent();
        TimelineItem sharedPhoto = photoItem(23L, "shared.jpg");
        when(timelineEventItemService.findByTimelineEventIds(anyCollection()))
                .thenReturn(List.of(TimelineEventItem.of(EVENT_ID, 23L)));
        when(timelineEventItemService.findByTimelineItemIds(anyCollection()))
                .thenReturn(List.of(
                        TimelineEventItem.of(EVENT_ID, 23L),
                        TimelineEventItem.of(12L, 23L)));
        when(timelineItemService.findByIds(anyCollection())).thenReturn(List.of(sharedPhoto));

        TimelineDeletionTransactionService.DeletionResult result =
                service.deleteEvent(SUBJECT_ID, EVENT_ID);

        assertThat(result).isEqualTo(
                new TimelineDeletionTransactionService.DeletionResult(0, 1, 0));
        verifyNoInteractions(timelinePhotoDeleteJobService);
        verify(timelineItemService).deleteByIds(List.of());
        verify(timelineEventService).deleteById(EVENT_ID);
    }

    @Test
    void deleteEvent_invalidPhotoPayloadsSkipJobsButHardDeleteAndReturnCounts() {
        stubOwnedDraftEvent();
        TimelineItem broken = brokenPhotoItem(31L);
        TimelineItem blank = photoItem(32L, " ");
        TimelineItem valid = photoItem(33L, "good.jpg");
        TimelineItem jsonNull = nullPhotoItem(34L);
        when(timelineEventItemService.findByTimelineEventIds(anyCollection()))
                .thenReturn(List.of(
                        TimelineEventItem.of(EVENT_ID, 31L),
                        TimelineEventItem.of(EVENT_ID, 32L),
                        TimelineEventItem.of(EVENT_ID, 33L),
                        TimelineEventItem.of(EVENT_ID, 34L)));
        when(timelineEventItemService.findByTimelineItemIds(anyCollection()))
                .thenReturn(List.of(
                        TimelineEventItem.of(EVENT_ID, 31L),
                        TimelineEventItem.of(EVENT_ID, 32L),
                        TimelineEventItem.of(EVENT_ID, 33L),
                        TimelineEventItem.of(EVENT_ID, 34L)));
        when(timelineItemService.findByIds(anyCollection()))
                .thenReturn(List.of(broken, blank, valid, jsonNull));
        String validKey = PhotoObjectKeys.subjectFullKey("good.jpg", SUBJECT_ID);
        when(timelinePhotoDeleteJobService.insertIfAbsent(33L, validKey)).thenReturn(true);

        TimelineDeletionTransactionService.DeletionResult result =
                service.deleteEvent(SUBJECT_ID, EVENT_ID);

        assertThat(result).isEqualTo(
                new TimelineDeletionTransactionService.DeletionResult(1, 0, 3));
        verify(timelinePhotoDeleteJobService).insertIfAbsent(33L, validKey);
        verify(timelinePhotoDeleteJobService, never())
                .insertIfAbsent(eq(31L), anyString());
        verify(timelinePhotoDeleteJobService, never())
                .insertIfAbsent(eq(32L), anyString());
        verify(timelinePhotoDeleteJobService, never())
                .insertIfAbsent(eq(34L), anyString());
        ArgumentCaptor<Collection<Long>> immediateDeleteIds =
                ArgumentCaptor.forClass(Collection.class);
        verify(timelineItemService).deleteByIds(immediateDeleteIds.capture());
        assertThat(immediateDeleteIds.getValue()).containsExactlyInAnyOrder(31L, 32L, 34L);
    }

    @Test
    void deleteEvent_existingDeleteJobIsNotCountedAsNewSchedule() {
        stubOwnedDraftEvent();
        TimelineItem photo = photoItem(21L, "a.jpg");
        when(timelineEventItemService.findByTimelineEventIds(anyCollection()))
                .thenReturn(List.of(TimelineEventItem.of(EVENT_ID, 21L)));
        when(timelineEventItemService.findByTimelineItemIds(anyCollection()))
                .thenReturn(List.of(TimelineEventItem.of(EVENT_ID, 21L)));
        when(timelineItemService.findByIds(anyCollection())).thenReturn(List.of(photo));
        String objectKey = PhotoObjectKeys.subjectFullKey("a.jpg", SUBJECT_ID);
        when(timelinePhotoDeleteJobService.insertIfAbsent(21L, objectKey)).thenReturn(false);

        TimelineDeletionTransactionService.DeletionResult result =
                service.deleteEvent(SUBJECT_ID, EVENT_ID);

        assertThat(result).isEqualTo(
                new TimelineDeletionTransactionService.DeletionResult(0, 0, 0));
        verify(timelineEventService).deleteById(EVENT_ID);
        verify(timelineItemService).deleteByIds(List.of());
    }

    @Test
    void deleteDailyRecord_enqueuesPhotoBeforeRecordHardDeleteAndReturnsCounts() {
        when(dailyRecordService.findById(RECORD_ID))
                .thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
        when(timelineEventService.findByDailyRecordId(RECORD_ID))
                .thenReturn(List.of(event(EVENT_ID), event(12L)));
        TimelineItem photo = photoItem(21L, "record.jpg");
        when(timelineEventItemService.findByTimelineEventIds(anyCollection()))
                .thenReturn(List.of(
                        TimelineEventItem.of(EVENT_ID, 21L),
                        TimelineEventItem.of(12L, 21L)));
        when(timelineEventItemService.findByTimelineItemIds(anyCollection()))
                .thenReturn(List.of(
                        TimelineEventItem.of(EVENT_ID, 21L),
                        TimelineEventItem.of(12L, 21L)));
        when(timelineItemService.findByIds(anyCollection())).thenReturn(List.of(photo));
        String objectKey = PhotoObjectKeys.subjectFullKey("record.jpg", SUBJECT_ID);
        when(timelinePhotoDeleteJobService.insertIfAbsent(21L, objectKey)).thenReturn(true);

        TimelineDeletionTransactionService.DeletionResult result =
                service.deleteDailyRecord(SUBJECT_ID, RECORD_ID);

        assertThat(result).isEqualTo(
                new TimelineDeletionTransactionService.DeletionResult(1, 0, 0));
        InOrder order = inOrder(
                timelinePhotoDeleteJobService,
                dailyRecordService,
                timelineItemService);
        order.verify(timelinePhotoDeleteJobService).insertIfAbsent(21L, objectKey);
        order.verify(dailyRecordService).deleteById(RECORD_ID);
        order.verify(timelineItemService).deleteByIds(List.of());
    }

    @Test
    void deleteEvent_missingEventOnRecheckIs404WithoutMutation() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteEvent(SUBJECT_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(timelineEventService, never()).deleteById(anyLong());
        verify(timelineItemService, never()).deleteByIds(anyCollection());
        verifyNoInteractions(timelinePhotoDeleteJobService);
    }

    @Test
    void deleteEvent_foreignRecordOnRecheckIs404WithoutMutation() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID)));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(OTHER_SUBJECT_ID)));

        assertThatThrownBy(() -> service.deleteEvent(SUBJECT_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));
        verify(timelineEventService, never()).deleteById(anyLong());
        verifyNoInteractions(timelinePhotoDeleteJobService);
    }

    @Test
    void deleteEvent_savedRecordOnRecheckIsStillDeletable() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID)));
        DailyRecord saved = draftRecordOf(SUBJECT_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        service.deleteEvent(SUBJECT_ID, EVENT_ID);

        verify(timelineEventService).deleteById(EVENT_ID);
    }

    @Test
    void detachEventItem_sharedPhotoRemovesOnlyTargetJunctionWithoutJob() {
        stubOwnedDraftEvent();
        when(timelineItemService.findById(21L)).thenReturn(Optional.of(photoItem(21L, "shared.jpg")));
        when(timelineEventItemService.isLinked(EVENT_ID, 21L)).thenReturn(true);
        when(timelineEventItemService.deleteLink(EVENT_ID, 21L)).thenReturn(1);
        when(timelineEventItemService.findByTimelineItemIds(List.of(21L)))
                .thenReturn(List.of(TimelineEventItem.of(12L, 21L)));

        TimelineDeletionTransactionService.DeletionResult result =
                service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L);

        assertThat(result).isEqualTo(
                new TimelineDeletionTransactionService.DeletionResult(0, 1, 0));
        verify(timelineEventItemService).deleteLink(EVENT_ID, 21L);
        verify(timelineItemService, never()).deleteByIds(anyCollection());
        verifyNoInteractions(timelinePhotoDeleteJobService);
    }

    @Test
    void detachEventItem_lastReferenceEnqueuesJobAndPreservesItem() {
        stubOwnedDraftEvent();
        when(timelineItemService.findById(21L)).thenReturn(Optional.of(photoItem(21L, "last.jpg")));
        when(timelineEventItemService.isLinked(EVENT_ID, 21L)).thenReturn(true);
        when(timelineEventItemService.deleteLink(EVENT_ID, 21L)).thenReturn(1);
        when(timelineEventItemService.findByTimelineItemIds(List.of(21L))).thenReturn(List.of());
        String objectKey = PhotoObjectKeys.subjectFullKey("last.jpg", SUBJECT_ID);
        when(timelinePhotoDeleteJobService.insertIfAbsent(21L, objectKey)).thenReturn(true);

        TimelineDeletionTransactionService.DeletionResult result =
                service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L);

        assertThat(result).isEqualTo(
                new TimelineDeletionTransactionService.DeletionResult(1, 0, 0));
        verify(timelinePhotoDeleteJobService).insertIfAbsent(21L, objectKey);
        // valid PHOTO는 immediate delete 목록이 비어 Item이 보존된다.
        verify(timelineItemService).deleteByIds(List.of());
    }

    @Test
    void detachEventItem_lastReferenceBrokenPhotoSkipsJobAndHardDeletesItem() {
        stubOwnedDraftEvent();
        when(timelineItemService.findById(21L)).thenReturn(Optional.of(brokenPhotoItem(21L)));
        when(timelineEventItemService.isLinked(EVENT_ID, 21L)).thenReturn(true);
        when(timelineEventItemService.deleteLink(EVENT_ID, 21L)).thenReturn(1);
        when(timelineEventItemService.findByTimelineItemIds(List.of(21L))).thenReturn(List.of());

        TimelineDeletionTransactionService.DeletionResult result =
                service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L);

        assertThat(result).isEqualTo(
                new TimelineDeletionTransactionService.DeletionResult(0, 0, 1));
        verify(timelineItemService).deleteByIds(List.of(21L));
        verifyNoInteractions(timelinePhotoDeleteJobService);
    }

    @Test
    void detachEventItem_existingDeleteJobStillRemovesJunctionAndPreservesItem() {
        stubOwnedDraftEvent();
        when(timelineItemService.findById(21L)).thenReturn(Optional.of(photoItem(21L, "dup.jpg")));
        when(timelineEventItemService.isLinked(EVENT_ID, 21L)).thenReturn(true);
        when(timelineEventItemService.deleteLink(EVENT_ID, 21L)).thenReturn(1);
        when(timelineEventItemService.findByTimelineItemIds(List.of(21L))).thenReturn(List.of());
        String objectKey = PhotoObjectKeys.subjectFullKey("dup.jpg", SUBJECT_ID);
        when(timelinePhotoDeleteJobService.insertIfAbsent(21L, objectKey)).thenReturn(false);

        TimelineDeletionTransactionService.DeletionResult result =
                service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L);

        assertThat(result).isEqualTo(
                new TimelineDeletionTransactionService.DeletionResult(0, 0, 0));
        verify(timelineEventItemService).deleteLink(EVENT_ID, 21L);
        verify(timelineItemService).deleteByIds(List.of());
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
        verifyNoInteractions(timelinePhotoDeleteJobService);
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
        verifyNoInteractions(timelinePhotoDeleteJobService);
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
        verifyNoInteractions(timelinePhotoDeleteJobService);
    }

    @Test
    void detachEventItem_missingItemIs404WithoutMutation() {
        stubOwnedDraftEvent();
        when(timelineItemService.findById(21L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(timelineEventItemService, never()).deleteLink(anyLong(), anyLong());
        verifyNoInteractions(timelinePhotoDeleteJobService);
    }

    @Test
    void detachEventItem_missingEventIs404WithoutMutation() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(timelineEventItemService, never()).deleteLink(anyLong(), anyLong());
        verifyNoInteractions(timelinePhotoDeleteJobService);
    }

    @Test
    void detachEventItem_foreignRecordIs404WithoutMutation() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID)));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(OTHER_SUBJECT_ID)));

        assertThatThrownBy(() -> service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(timelineEventItemService, never()).deleteLink(anyLong(), anyLong());
        verifyNoInteractions(timelinePhotoDeleteJobService);
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
        when(timelineEventItemService.findByTimelineItemIds(List.of(21L)))
                .thenReturn(List.of(TimelineEventItem.of(12L, 21L)));

        service.detachEventItem(SUBJECT_ID, EVENT_ID, 21L);

        verify(timelineEventItemService).deleteLink(EVENT_ID, 21L);
    }

    @Test
    void deleteDailyRecord_missingRecordOnRecheckIs404WithoutMutation() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDailyRecord(SUBJECT_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-404));

        verify(dailyRecordService, never()).deleteById(anyLong());
        verify(timelineItemService, never()).deleteByIds(anyCollection());
        verifyNoInteractions(timelinePhotoDeleteJobService);
    }

    @Test
    void deleteDailyRecord_savedRecordOnRecheckIsStillDeletable() {
        DailyRecord saved = draftRecordOf(SUBJECT_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        service.deleteDailyRecord(SUBJECT_ID, RECORD_ID);

        verify(dailyRecordService).deleteById(RECORD_ID);
    }
}
