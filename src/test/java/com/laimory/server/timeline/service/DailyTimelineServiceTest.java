package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.PhotoPayloadResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineEventSuggestionDto;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 하루 타임라인 오케스트레이터가 leaf 서비스 + 검증기를 올바르게 합성하는지 단위 검증. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class DailyTimelineServiceTest {

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private TimelineItemService timelineItemService;
    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;
    @Mock
    private TimelineEventSuggestionValidator timelineEventSuggestionValidator;
    @Mock
    private TimelineItemResponseMapper timelineItemResponseMapper;

    @InjectMocks
    private DailyTimelineService dailyTimelineService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Long USER_ID = 7L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 6, 17);
    private static final LocalDateTime RECORD_AT = LocalDateTime.of(2026, 6, 17, 12, 0);
    private static final String ZONE = "Asia/Seoul";
    private static final String TASK_ID = "task-1";

    private TimelineDraftSourceItem photoRow(long pk, LocalDateTime startAt) {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of(TASK_ID, USER_ID, RECORD_DATE, RECORD_AT, ZONE, ItemType.PHOTO,
                startAt, null, "summary-" + pk,
                MAPPER.valueToTree(new PhotoPayload("uri" + pk, "content://" + pk, 1.0, 2.0)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", pk);
        return row;
    }

    private TimelineDraftSourceItem locationRow(long pk, LocalDateTime startAt) {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of(TASK_ID, USER_ID, RECORD_DATE, RECORD_AT, ZONE, ItemType.LOCATION,
                startAt, null, "summary-" + pk,
                MAPPER.valueToTree(new LocationPayload("place", "area", 3.0, 4.0)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", pk);
        return row;
    }

    // --- appendDailyTimeline (finalize) ---

    @Test
    void appendDailyTimeline_reusesRecordAndCopiesFromDraftRowThenDeletesDrafts() {
        DailyRecord existing = DailyRecord.createDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(existing, "dailyRecordId", 100L);
        when(dailyRecordService.findOrCreateDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE)).thenReturn(existing);
        stubEventSaveWithSequentialIds();

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftSourceItem> draftRows = List.of(photoRow(10, t));
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto("아침", "산책", t, t.plusHours(1), List.of(10L)));

        Long result = dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, draftRows, events);

        assertThat(result).isEqualTo(100L);
        verify(timelineEventSuggestionValidator).validate(draftRows, events);
        verify(timelineEventService, times(1)).save(any());

        // 아이템은 draft 행에서 그대로 복사된다(itemType/start/payload).
        ArgumentCaptor<TimelineItem> itemCaptor = ArgumentCaptor.forClass(TimelineItem.class);
        verify(timelineItemService, times(1)).save(itemCaptor.capture());
        TimelineItem saved = itemCaptor.getValue();
        assertThat(saved.getItemType()).isEqualTo(ItemType.PHOTO);
        assertThat(saved.getStartAt()).isEqualTo(t);
        assertThat(saved.getPayload().get("filename").asText()).isEqualTo("uri10");

        // 소비한 draft 행을 taskId로 삭제한다(같은 트랜잭션).
        verify(timelineDraftSourceItemService).deleteByTaskId(TASK_ID);
    }

    @Test
    void appendDailyTimeline_createsDraftWhenAbsent_andMapsItemsToCorrectEventByItemId() {
        DailyRecord created = DailyRecord.createDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(created, "dailyRecordId", 200L);
        when(dailyRecordService.findOrCreateDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE)).thenReturn(created);
        stubEventSaveWithSequentialIds();

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 8, 0);
        List<TimelineDraftSourceItem> draftRows = List.of(
                photoRow(10, t),
                locationRow(11, t.plusHours(1)),
                photoRow(12, t.plusHours(2)));
        // 이벤트 A: item PK 10,12 / 이벤트 B: item PK 11
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto("A", "subA", t, t.plusHours(2), List.of(10L, 12L)),
                new TimelineEventSuggestionDto("B", "subB", t.plusHours(1), null, List.of(11L)));

        Long result = dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, draftRows, events);

        assertThat(result).isEqualTo(200L);
        verify(timelineEventService, times(2)).save(any());

        // item이 올바른 이벤트(첫 이벤트 id=1, 둘째 이벤트 id=2)로 매핑되는지 확인.
        ArgumentCaptor<TimelineItem> itemCaptor = ArgumentCaptor.forClass(TimelineItem.class);
        verify(timelineItemService, times(3)).save(itemCaptor.capture());
        List<TimelineItem> savedItems = itemCaptor.getAllValues();
        assertThat(savedItems.get(0).getTimelineEventId()).isEqualTo(1L);
        assertThat(savedItems.get(0).getStartAt()).isEqualTo(t);
        assertThat(savedItems.get(1).getTimelineEventId()).isEqualTo(1L);
        assertThat(savedItems.get(1).getStartAt()).isEqualTo(t.plusHours(2));
        assertThat(savedItems.get(2).getTimelineEventId()).isEqualTo(2L);
        assertThat(savedItems.get(2).getStartAt()).isEqualTo(t.plusHours(1));
        verify(timelineDraftSourceItemService).deleteByTaskId(TASK_ID);
    }

    @Test
    void appendDailyTimeline_rejectsSavedRecord() {
        DailyRecord saved = DailyRecord.createDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(saved, "dailyRecordId", 100L);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findOrCreateDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE)).thenReturn(saved);

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftSourceItem> draftRows = List.of(photoRow(10, t));
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto("아침", "산책", t, t.plusHours(1), List.of(10L)));

        assertThatThrownBy(() -> dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, draftRows, events))
                .isInstanceOf(IllegalStateException.class);
        // SAVED record면 이벤트를 하나도 저장하지 않고 draft도 삭제하지 않는다(롤백 대상).
        verify(timelineEventService, never()).save(any());
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
    }

    @Test
    void appendDailyTimeline_validationFails_doesNotPersistOrDeleteDrafts() {
        // 검증기가 위반을 던지면(record 생성 전 첫 단계) 영속·삭제가 일어나지 않는다(전체 롤백).
        doThrow(new IllegalArgumentException("event references unknown itemId: 1"))
                .when(timelineEventSuggestionValidator).validate(any(), any());

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftSourceItem> draftRows = List.of(photoRow(10, t));
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto("아침", "산책", t, t.plusHours(1), List.of(99L)));

        assertThatThrownBy(() -> dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, draftRows, events))
                .isInstanceOf(IllegalArgumentException.class);
        verify(dailyRecordService, never()).findOrCreateDraft(any(), any(), any(), any());
        verify(timelineEventService, never()).save(any());
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
    }

    // --- getDailyTimeline (읽기) ---

    @Test
    void getDailyTimeline_assemblesRecordEventsAndItems() {
        DailyRecord record = DailyRecord.createDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(record, "dailyRecordId", 300L);
        ReflectionTestUtils.setField(record, "emotionType", EmotionType.HAPPY);
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(record));

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 10, 0);
        TimelineEvent event = TimelineEvent.of(300L, t, t.plusHours(2), "제목", "부제목");
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        ReflectionTestUtils.setField(event, "memo", "내 메모");
        when(timelineEventService.findByDailyRecordId(300L)).thenReturn(List.of(event));

        LocationPayload location = new LocationPayload("카페", "강남", 3.0, 4.0);
        TimelineItem item0 = TimelineItem.of(11L, ItemType.PHOTO, t, null,
                MAPPER.valueToTree(new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://x", 1.0, 2.0)));
        ReflectionTestUtils.setField(item0, "timelineItemId", 21L);
        TimelineItem item1 = TimelineItem.of(11L, ItemType.LOCATION, t.plusHours(1), t.plusHours(2),
                MAPPER.valueToTree(location));
        ReflectionTestUtils.setField(item1, "timelineItemId", 22L);
        when(timelineItemService.findByTimelineEventId(11L)).thenReturn(List.of(item0, item1));

        // 매퍼가 PHOTO를 photoUrl로 구성해 응답을 만든다(URL 구성 로직은 TimelineItemResponseMapperTest에서 검증).
        // userId는 record의 user_id(7L)로 전달된다.
        TimelineItemResponse photoResponse = new TimelineItemResponse(21L, ItemType.PHOTO, t, null,
                MAPPER.valueToTree(new PhotoPayloadResponse("https://cdn.example/x", "content://x", 1.0, 2.0)));
        TimelineItemResponse locationResponse = new TimelineItemResponse(22L, ItemType.LOCATION,
                t.plusHours(1), t.plusHours(2), MAPPER.valueToTree(location));
        when(timelineItemResponseMapper.toResponse(item0, 7L)).thenReturn(photoResponse);
        when(timelineItemResponseMapper.toResponse(item1, 7L)).thenReturn(locationResponse);

        DailyTimelineResponse result = dailyTimelineService.getDailyTimeline(300L);

        assertThat(result.recordDate()).isEqualTo(RECORD_DATE);
        assertThat(result.emotionType()).isEqualTo(EmotionType.HAPPY);
        assertThat(result.events()).hasSize(1);

        TimelineEventResponse eventResponse = result.events().get(0);
        assertThat(eventResponse.timelineEventId()).isEqualTo(11L);
        assertThat(eventResponse.startAt()).isEqualTo(t);
        assertThat(eventResponse.endAt()).isEqualTo(t.plusHours(2));
        assertThat(eventResponse.title()).isEqualTo("제목");
        assertThat(eventResponse.subtitle()).isEqualTo("부제목");
        assertThat(eventResponse.memo()).isEqualTo("내 메모");
        assertThat(eventResponse.items()).hasSize(2);

        // 오케스트레이터는 매퍼가 만든 응답을 그대로 이벤트에 담는다(PHOTO는 photoUrl로 구성됨).
        TimelineItemResponse itemResponse0 = eventResponse.items().get(0);
        assertThat(itemResponse0.timelineItemId()).isEqualTo(21L);
        assertThat(itemResponse0.itemType()).isEqualTo(ItemType.PHOTO);
        assertThat(itemResponse0.startAt()).isEqualTo(t);
        assertThat(itemResponse0.endAt()).isNull();
        assertThat(itemResponse0.payload().get("photoUrl").asText()).isEqualTo("https://cdn.example/x");
        assertThat(itemResponse0.payload().get("clientPhotoUri").asText()).isEqualTo("content://x");
        assertThat(itemResponse0.payload().has("filename")).isFalse();
        assertThat(itemResponse0.payload().has("itemType")).isFalse();

        TimelineItemResponse itemResponse1 = eventResponse.items().get(1);
        assertThat(itemResponse1.timelineItemId()).isEqualTo(22L);
        assertThat(itemResponse1.itemType()).isEqualTo(ItemType.LOCATION);
        assertThat(itemResponse1.payload().get("placeName").asText()).isEqualTo("카페");
    }

    @Test
    void getDailyTimeline_throwsWhenRecordNotFound() {
        when(dailyRecordService.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dailyTimelineService.getDailyTimeline(999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("999");
    }

    /** 저장되는 이벤트마다 1부터 증가하는 id를 부여해 반환한다(item save가 event id를 참조할 수 있도록). */
    private void stubEventSaveWithSequentialIds() {
        when(timelineEventService.save(any())).thenAnswer(new org.mockito.stubbing.Answer<TimelineEvent>() {
            private long nextId = 1L;

            @Override
            public TimelineEvent answer(org.mockito.invocation.InvocationOnMock invocation) {
                TimelineEvent event = invocation.getArgument(0);
                ReflectionTestUtils.setField(event, "timelineEventId", nextId++);
                return event;
            }
        });
    }
}
