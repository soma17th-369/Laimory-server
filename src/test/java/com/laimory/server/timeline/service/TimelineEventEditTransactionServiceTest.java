package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.photo.PhotoUrlService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Event PATCH DB writer의 rawId 분류, payload 재구성, mutation 전 재검증을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TimelineEventEditTransactionServiceTest {

    private static final UUID SUBJECT_ID = id(7L);
    private static final UUID OTHER_SUBJECT_ID = id(999L);
    private static final Long EVENT_ID = 11L;
    private static final Long RECORD_ID = 100L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 7, 8);
    private static final LocalDateTime ORIGINAL_START = RECORD_DATE.atTime(9, 0);
    private static final LocalDateTime ORIGINAL_END = RECORD_DATE.atTime(10, 0);
    private static final LocalDateTime NEW_START = RECORD_DATE.atTime(14, 0);
    private static final LocalDateTime NEW_END = RECORD_DATE.atTime(15, 0);
    private static final String RAW_ID = "0190a1b2-0001-7000-8000-000000000001";
    private static final String RAW_ID_2 = "0190a1b2-0002-7000-8000-000000000002";
    private static final String FILENAME = "0190a1b2-0003-7000-8000-000000000003.jpg";
    private static final String PHOTO_URL = "https://cdn.example/user/photos/" + FILENAME;

    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineEventItemService timelineEventItemService;
    @Mock
    private TimelineItemService timelineItemService;
    @Mock
    private PhotoUrlService photoUrlService;

    private TimelineEventEditTransactionService service;

    @BeforeEach
    void setUp() {
        // 사진 분류·저장은 실제 공유 컴포넌트(mock leaf 주입)를 태워 "PATCH 동작이 리팩터 전후 동일"을
        // 기존 시나리오 단언 무수정으로 고정한다(개수 상한은 이 writer 시나리오와 무관).
        TimelineEventPhotoAddService photoAddService = new TimelineEventPhotoAddService(
                timelineEventItemService,
                timelineItemService,
                photoUrlService,
                new ObjectMapper(),
                20);
        service = new TimelineEventEditTransactionService(
                timelineEventService,
                dailyRecordService,
                photoAddService);
    }

    @Test
    void updateEvent_createsPhotoWithServerPayloadAndAppliesPresentMemo() {
        TimelineEvent event = stubOwnedDraftEvent();
        TimelineEventEditCommand command = command(true, " 새 메모 ", List.of(photo(RAW_ID, FILENAME)));
        stubTargetLinks(List.of(), Set.of());
        when(photoUrlService.buildSubjectUrl(FILENAME, SUBJECT_ID)).thenReturn(PHOTO_URL);
        when(timelineItemService.save(any(TimelineItem.class))).thenAnswer(invocation -> {
            TimelineItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "timelineItemId", 21L);
            return item;
        });
        service.updateEvent(SUBJECT_ID, EVENT_ID, command);

        assertThat(event.getEventType()).isEqualTo(TimelineEventType.MEAL);
        assertThat(event.getTitle()).isEqualTo("새 제목");
        assertThat(event.getSubtitle()).isEqualTo("새 부제");
        assertThat(event.getStartAt()).isEqualTo(NEW_START);
        assertThat(event.getEndAt()).isEqualTo(NEW_END);
        assertThat(event.getMemo()).isEqualTo(" 새 메모 ");

        ArgumentCaptor<TimelineItem> itemCaptor = ArgumentCaptor.forClass(TimelineItem.class);
        verify(timelineItemService).save(itemCaptor.capture());
        TimelineItem savedItem = itemCaptor.getValue();
        assertThat(savedItem.getItemType()).isEqualTo(ItemType.PHOTO);
        assertThat(savedItem.getRawId()).isEqualTo(RAW_ID);
        assertThat(savedItem.getStartAt()).isEqualTo(RECORD_DATE.atTime(14, 5));
        assertThat(savedItem.getEndAt()).isNull();
        JsonNode payload = savedItem.getPayload();
        assertThat(payload.path("filename").asText()).isEqualTo(FILENAME);
        assertThat(payload.path("clientPhotoUri").asText()).isEqualTo("content://photo/1");
        assertThat(payload.path("latitude").asDouble()).isEqualTo(37.5665);
        assertThat(payload.path("longitude").asDouble()).isEqualTo(126.9780);
        assertThat(payload.path("photoUrl").asText()).isEqualTo(PHOTO_URL);
        assertThat(payload.has("description")).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineEventItem>> linksCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineEventItemService).saveAll(linksCaptor.capture());
        assertThat(linksCaptor.getValue()).singleElement().satisfies(link -> {
            assertThat(link.getTimelineEventId()).isEqualTo(EVENT_ID);
            assertThat(link.getTimelineItemId()).isEqualTo(21L);
        });
    }

    @Test
    void updateEvent_targetAlreadyHasRawId_skipsPhotoAndPreservesOmittedMemo() {
        TimelineEvent event = stubOwnedDraftEvent();
        event.updateMemo("기존 메모");
        // 커밋 뒤 응답을 잃은 같은 PATCH의 재시도 — 대상 Event에 같은 rawId가 있으면 비교 없이 건너뛴다.
        stubTargetLinks(List.of(TimelineEventItem.of(EVENT_ID, 21L)), Set.of(RAW_ID));
        service.updateEvent(SUBJECT_ID, EVENT_ID, command(false, null, List.of(photo(RAW_ID, FILENAME))));

        assertThat(event.getMemo()).isEqualTo("기존 메모");
        verify(timelineItemService).findSavedRawIds(List.of(21L), Set.of(RAW_ID));
        verify(timelineItemService, never()).save(any());
        verify(timelineEventItemService, never()).saveAll(anyList());
        verify(photoUrlService, never()).buildSubjectUrl(any(), any());
    }

    @Test
    void updateEvent_multipleNewPhotos_saveEachAsNewItem() {
        TimelineEvent event = stubOwnedDraftEvent();
        String secondFilename = "0190a1b2-0004-7000-8000-000000000004.jpg";
        stubTargetLinks(List.of(), Set.of());
        when(photoUrlService.buildSubjectUrl(any(), any())).thenReturn(PHOTO_URL);
        AtomicLong nextItemId = new AtomicLong(21L);
        when(timelineItemService.save(any(TimelineItem.class))).thenAnswer(invocation -> {
            TimelineItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "timelineItemId", nextItemId.getAndIncrement());
            return item;
        });

        service.updateEvent(SUBJECT_ID, EVENT_ID, command(false, null,
                List.of(photo(RAW_ID, FILENAME), photo(RAW_ID_2, secondFilename))));

        verify(timelineItemService, times(2)).save(any(TimelineItem.class));
    }

    @Test
    void updateEvent_duplicateFilenameAmongNewPhotosFailsBeforeEventMutation() {
        TimelineEvent event = stubOwnedDraftEvent();
        event.updateMemo("기존 메모");
        stubTargetLinks(List.of(), Set.of());

        assertThatThrownBy(() -> service.updateEvent(SUBJECT_ID, EVENT_ID,
                command(true, "새 메모", List.of(photo(RAW_ID, FILENAME), photo(RAW_ID_2, FILENAME)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("filename is duplicated across new photos");

        assertOriginalState(event, "기존 메모");
        verifyNoWrites();
    }

    @Test
    void updateEvent_foreignOwnerOnTransactionRecheckIsHiddenAs404() {
        TimelineEvent event = event(EVENT_ID);
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(record(OTHER_SUBJECT_ID, DailyRecordStatus.DRAFT)));

        assertThatThrownBy(() -> service.updateEvent(SUBJECT_ID, EVENT_ID, command(false, null, List.of())))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        assertOriginalState(event, null);
        verifyNoWrites();
    }

    @Test
    void updateEvent_savedRecordOnTransactionRecheckIsStillEditable() {
        TimelineEvent event = event(EVENT_ID);
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(RECORD_ID))
                .thenReturn(Optional.of(record(SUBJECT_ID, DailyRecordStatus.SAVED)));

        service.updateEvent(SUBJECT_ID, EVENT_ID, command(false, null, List.of()));

        assertThat(event.getEventType()).isEqualTo(TimelineEventType.MEAL);
        assertThat(event.getTitle()).isEqualTo("새 제목");
        assertThat(event.getStartAt()).isEqualTo(NEW_START);
        assertThat(event.getEndAt()).isEqualTo(NEW_END);
    }

    @Test
    void updateEvent_noChangeSignalsPreserveCurrentValuesExceptEndAt() {
        TimelineEvent event = stubOwnedDraftEvent();
        event.updateMemo("기존 메모");

        service.updateEvent(SUBJECT_ID, EVENT_ID, new TimelineEventEditCommand(
                null, null, false, null, null, null, false, null, List.of()));

        assertThat(event.getEventType()).isEqualTo(TimelineEventType.REST);
        assertThat(event.getTitle()).isEqualTo("원래 제목");
        assertThat(event.getSubtitle()).isEqualTo("원래 부제");
        assertThat(event.getStartAt()).isEqualTo(ORIGINAL_START);
        assertThat(event.getEndAt()).isNull();
        assertThat(event.getMemo()).isEqualTo("기존 메모");
        verifyNoWrites();
    }

    @Test
    void updateEvent_changedNullValuesClearSubtitleAndMemo() {
        TimelineEvent event = stubOwnedDraftEvent();
        event.updateMemo("기존 메모");

        service.updateEvent(SUBJECT_ID, EVENT_ID, new TimelineEventEditCommand(
                null, null, true, null, null, ORIGINAL_END, true, null, List.of()));

        assertThat(event.getSubtitle()).isNull();
        assertThat(event.getMemo()).isNull();
        assertThat(event.getEndAt()).isEqualTo(ORIGINAL_END);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateEvent_invalidMergedTimeRangeFailsBeforeAnyMutation(boolean replaceStart) {
        TimelineEvent event = stubOwnedDraftEvent();
        event.updateMemo("기존 메모");

        assertThatThrownBy(() -> service.updateEvent(SUBJECT_ID, EVENT_ID, new TimelineEventEditCommand(
                TimelineEventType.MEAL, "새 제목", true, null,
                replaceStart ? NEW_START : null,
                replaceStart ? NEW_START.minusNanos(1) : ORIGINAL_START.minusNanos(1),
                true, null, List.of(photo(RAW_ID, FILENAME)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endAt is before startAt");

        assertOriginalState(event, "기존 메모");
        verifyNoWrites();
        verify(timelineEventService, never()).findByDailyRecordId(any());
    }

    @Test
    void updateEvent_endAtCanEqualPreservedStartAt() {
        TimelineEvent event = stubOwnedDraftEvent();
        service.updateEvent(SUBJECT_ID, EVENT_ID, new TimelineEventEditCommand(
                null, null, false, null, null, ORIGINAL_START, false, null, List.of()));
        assertThat(event.getStartAt()).isEqualTo(ORIGINAL_START);
        assertThat(event.getEndAt()).isEqualTo(ORIGINAL_START);
    }

    private TimelineEvent stubOwnedDraftEvent() {
        TimelineEvent event = event(EVENT_ID);
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(RECORD_ID))
                .thenReturn(Optional.of(record(SUBJECT_ID, DailyRecordStatus.DRAFT)));
        return event;
    }

    /** 대상 Event의 junction과, 그 Item 중 요청 rawId와 겹치는 rawId 집합을 stub한다(record 전체 조회는 없다). */
    private void stubTargetLinks(List<TimelineEventItem> targetLinks, Set<String> linkedRawIds) {
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID)).thenReturn(targetLinks);
        when(timelineItemService.findSavedRawIds(anyCollection(), anyCollection())).thenReturn(linkedRawIds);
    }

    private TimelineEvent event(Long eventId) {
        TimelineEvent event = TimelineEvent.of(
                RECORD_ID, TimelineEventType.REST, ORIGINAL_START, ORIGINAL_END, "원래 제목", "원래 부제", null, null, null);
        ReflectionTestUtils.setField(event, "timelineEventId", eventId);
        return event;
    }

    private DailyRecord record(UUID subjectId, DailyRecordStatus status) {
        DailyRecord record = DailyRecord.createDraft(
                subjectId, RECORD_DATE, RECORD_DATE.atTime(12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        ReflectionTestUtils.setField(record, "status", status);
        return record;
    }

    private TimelineEventEditCommand command(boolean memoChanged, String memo,
                                             List<TimelineEventPhotoAddService.PhotoToAdd> photos) {
        return new TimelineEventEditCommand(
                TimelineEventType.MEAL,
                "새 제목",
                true,
                "새 부제",
                NEW_START,
                NEW_END,
                memoChanged,
                memo,
                photos);
    }

    private TimelineEventPhotoAddService.PhotoToAdd photo(String rawId, String filename) {
        return new TimelineEventPhotoAddService.PhotoToAdd(
                rawId,
                RECORD_DATE.atTime(14, 5),
                null,
                filename,
                "content://photo/1",
                37.5665,
                126.9780);
    }

    private void assertOriginalState(TimelineEvent event, String memo) {
        assertThat(event.getEventType()).isEqualTo(TimelineEventType.REST);
        assertThat(event.getTitle()).isEqualTo("원래 제목");
        assertThat(event.getSubtitle()).isEqualTo("원래 부제");
        assertThat(event.getStartAt()).isEqualTo(ORIGINAL_START);
        assertThat(event.getEndAt()).isEqualTo(ORIGINAL_END);
        assertThat(event.getMemo()).isEqualTo(memo);
    }

    private void verifyNoWrites() {
        verify(timelineItemService, never()).save(any());
        verify(timelineEventItemService, never()).saveAll(anyList());
        verify(photoUrlService, never()).buildSubjectUrl(any(), any());
    }
}
