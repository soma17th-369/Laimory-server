package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Event PATCH DB writer의 rawId 분류, payload 재구성, mutation 전 재검증을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TimelineEventEditTransactionServiceTest {

    private static final long USER_ID = 7L;
    private static final Long EVENT_ID = 11L;
    private static final Long OTHER_EVENT_ID = 12L;
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
        service = new TimelineEventEditTransactionService(
                timelineEventService,
                dailyRecordService,
                timelineEventItemService,
                timelineItemService,
                photoUrlService,
                new ObjectMapper());
    }

    @Test
    void updateEvent_createsPhotoWithServerPayloadAndAppliesPresentMemo() {
        TimelineEvent event = stubOwnedDraftEvent();
        TimelineEventEditCommand command = command(true, " 새 메모 ", List.of(photo(RAW_ID, FILENAME)));
        stubRecordGraph(List.of(event), List.of(), List.of());
        when(photoUrlService.buildUrl(FILENAME, USER_ID)).thenReturn(PHOTO_URL);
        when(timelineItemService.save(any(TimelineItem.class))).thenAnswer(invocation -> {
            TimelineItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "timelineItemId", 21L);
            return item;
        });
        service.updateEvent(USER_ID, EVENT_ID, command);

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
    void updateEvent_targetAlreadyHasRawId_isPhotoNoOpAndOmittedMemoIsPreserved() {
        TimelineEvent event = stubOwnedDraftEvent();
        event.updateMemo("기존 메모");
        TimelineItem existing = item(21L, ItemType.PHOTO, RAW_ID);
        TimelineEventItem targetLink = TimelineEventItem.of(EVENT_ID, 21L);
        stubRecordGraph(List.of(event), List.of(targetLink), List.of(existing));
        service.updateEvent(USER_ID, EVENT_ID, command(false, null, List.of(photo(RAW_ID, FILENAME))));

        assertThat(event.getMemo()).isEqualTo("기존 메모");
        verify(timelineItemService, never()).save(any());
        verify(timelineEventItemService, never()).saveAll(anyList());
        verify(photoUrlService, never()).buildUrl(any(), anyLong());
    }

    @Test
    void updateEvent_otherEventHasLegacyDuplicateRawId_reusesLowestItemId() {
        TimelineEvent event = stubOwnedDraftEvent();
        TimelineEvent other = event(OTHER_EVENT_ID);
        TimelineItem higherId = item(30L, ItemType.PHOTO, RAW_ID);
        TimelineItem lowerId = item(20L, ItemType.PHOTO, RAW_ID);
        stubRecordGraph(
                List.of(event, other),
                List.of(TimelineEventItem.of(OTHER_EVENT_ID, 30L), TimelineEventItem.of(OTHER_EVENT_ID, 20L)),
                List.of(higherId, lowerId));
        service.updateEvent(USER_ID, EVENT_ID, command(false, null, List.of(photo(RAW_ID, FILENAME))));

        verify(timelineItemService, never()).save(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineEventItem>> linksCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineEventItemService).saveAll(linksCaptor.capture());
        assertThat(linksCaptor.getValue()).singleElement().satisfies(link -> {
            assertThat(link.getTimelineEventId()).isEqualTo(EVENT_ID);
            assertThat(link.getTimelineItemId()).isEqualTo(20L);
        });
        verify(photoUrlService, never()).buildUrl(any(), anyLong());
    }

    @Test
    void updateEvent_nonPhotoRawIdConflictFailsBeforeEventMutation() {
        TimelineEvent event = stubOwnedDraftEvent();
        event.updateMemo("기존 메모");
        TimelineEvent other = event(OTHER_EVENT_ID);
        TimelineItem conflicting = item(21L, ItemType.HEALTH, RAW_ID);
        stubRecordGraph(
                List.of(event, other),
                List.of(TimelineEventItem.of(OTHER_EVENT_ID, 21L)),
                List.of(conflicting));

        assertThatThrownBy(() -> service.updateEvent(
                USER_ID, EVENT_ID, command(true, "새 메모", List.of(photo(RAW_ID, FILENAME)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rawId is already used by a non-PHOTO item");

        assertOriginalState(event, "기존 메모");
        verifyNoWrites();
    }

    @Test
    void updateEvent_duplicateFilenameAmongNewPhotosFailsBeforeEventMutation() {
        TimelineEvent event = stubOwnedDraftEvent();
        event.updateMemo("기존 메모");
        stubRecordGraph(List.of(event), List.of(), List.of());

        assertThatThrownBy(() -> service.updateEvent(USER_ID, EVENT_ID,
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
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(record(999L, DailyRecordStatus.DRAFT)));

        assertThatThrownBy(() -> service.updateEvent(USER_ID, EVENT_ID, command(false, null, List.of())))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        assertOriginalState(event, null);
        verifyNoWrites();
    }

    @Test
    void updateEvent_savedRecordOnTransactionRecheckIs1003() {
        TimelineEvent event = event(EVENT_ID);
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(RECORD_ID))
                .thenReturn(Optional.of(record(USER_ID, DailyRecordStatus.SAVED)));

        assertThatThrownBy(() -> service.updateEvent(USER_ID, EVENT_ID, command(false, null, List.of())))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1003);
                });

        assertOriginalState(event, null);
        verifyNoWrites();
    }

    private TimelineEvent stubOwnedDraftEvent() {
        TimelineEvent event = event(EVENT_ID);
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(RECORD_ID))
                .thenReturn(Optional.of(record(USER_ID, DailyRecordStatus.DRAFT)));
        return event;
    }

    private void stubRecordGraph(List<TimelineEvent> events, List<TimelineEventItem> links,
                                 List<TimelineItem> matchingItems) {
        when(timelineEventService.findByDailyRecordId(RECORD_ID)).thenReturn(events);
        when(timelineEventItemService.findByTimelineEventIds(
                events.stream().map(TimelineEvent::getTimelineEventId).toList())).thenReturn(links);
        when(timelineItemService.findByIdsAndRawIds(anyCollection(), anyCollection())).thenReturn(matchingItems);
    }

    private TimelineEvent event(Long eventId) {
        TimelineEvent event = TimelineEvent.of(
                RECORD_ID, TimelineEventType.REST, ORIGINAL_START, ORIGINAL_END, "원래 제목", "원래 부제");
        ReflectionTestUtils.setField(event, "timelineEventId", eventId);
        return event;
    }

    private DailyRecord record(long userId, DailyRecordStatus status) {
        DailyRecord record = DailyRecord.createDraft(
                userId, RECORD_DATE, RECORD_DATE.atTime(12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        ReflectionTestUtils.setField(record, "status", status);
        return record;
    }

    private TimelineItem item(Long itemId, ItemType itemType, String rawId) {
        TimelineItem item = TimelineItem.of(
                itemType, rawId, RECORD_DATE.atTime(8, 0), null,
                new ObjectMapper().createObjectNode().put("stored", true));
        ReflectionTestUtils.setField(item, "timelineItemId", itemId);
        return item;
    }

    private TimelineEventEditCommand command(boolean memoPresent, String memo,
                                             List<TimelineEventEditCommand.PhotoToAdd> photos) {
        return new TimelineEventEditCommand(
                TimelineEventType.MEAL,
                "새 제목",
                "새 부제",
                NEW_START,
                NEW_END,
                memoPresent,
                memo,
                photos);
    }

    private TimelineEventEditCommand.PhotoToAdd photo(String rawId, String filename) {
        return new TimelineEventEditCommand.PhotoToAdd(
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
        verify(photoUrlService, never()).buildUrl(any(), anyLong());
    }
}
