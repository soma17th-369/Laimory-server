package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.CardSuggestionDto;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 하루 타임라인 오케스트레이터가 3개 leaf 서비스를 올바르게 합성하는지 단위 검증. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class DailyTimelineServiceTest {

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private TimelineItemService timelineItemService;
    // 실제 ObjectMapper. valueToTree가 실 동작해야 하므로 mock이 아닌 spy로 주입한다.
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DailyTimelineService dailyTimelineService;

    private static final Long USER_ID = 7L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 6, 17);

    // --- appendDailyTimeline (쓰기) ---

    @Test
    void appendDailyTimeline_reusesRecordFromFindOrCreate() {
        DailyRecord existing = DailyRecord.createDraft(USER_ID, RECORD_DATE);
        ReflectionTestUtils.setField(existing, "dailyRecordId", 100L);
        when(dailyRecordService.findOrCreateDraft(USER_ID, RECORD_DATE)).thenReturn(existing);
        stubEventSaveWithSequentialIds();

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, ItemType.PHOTO, t, null, "summary-0", new PhotoPayload("uri", 1.0, 2.0)));
        List<CardSuggestionDto> cards = List.of(
                new CardSuggestionDto("아침", "산책", t, t.plusHours(1), List.of(0)));

        Long result = dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, sources, cards);

        assertThat(result).isEqualTo(100L);
        verify(timelineEventService, times(1)).save(any());
        verify(timelineItemService, times(1)).save(any());
    }

    @Test
    void appendDailyTimeline_createsDraftWhenAbsent_andMapsItemsToCorrectEventByItemId() {
        DailyRecord created = DailyRecord.createDraft(USER_ID, RECORD_DATE);
        ReflectionTestUtils.setField(created, "dailyRecordId", 200L);
        when(dailyRecordService.findOrCreateDraft(USER_ID, RECORD_DATE)).thenReturn(created);
        stubEventSaveWithSequentialIds();

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 8, 0);
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, ItemType.PHOTO, t, null, "s0", new PhotoPayload("uri0", 1.0, 2.0)),
                new SourceItemDto(1, ItemType.LOCATION, t.plusHours(1), null, "s1",
                        new LocationPayload("place", "area", 3.0, 4.0)),
                new SourceItemDto(2, ItemType.PHOTO, t.plusHours(2), null, "s2", new PhotoPayload("uri2", 5.0, 6.0)));
        // 이벤트 A: item 0,2 / 이벤트 B: item 1
        List<CardSuggestionDto> cards = List.of(
                new CardSuggestionDto("A", "subA", t, t.plusHours(2), List.of(0, 2)),
                new CardSuggestionDto("B", "subB", t.plusHours(1), null, List.of(1)));

        Long result = dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, sources, cards);

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
    }

    @Test
    void appendDailyTimeline_rejectsSavedRecord() {
        DailyRecord saved = DailyRecord.createDraft(USER_ID, RECORD_DATE);
        ReflectionTestUtils.setField(saved, "dailyRecordId", 100L);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findOrCreateDraft(USER_ID, RECORD_DATE)).thenReturn(saved);

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, ItemType.PHOTO, t, null, "summary-0", new PhotoPayload("uri", 1.0, 2.0)));
        List<CardSuggestionDto> cards = List.of(
                new CardSuggestionDto("아침", "산책", t, t.plusHours(1), List.of(0)));

        assertThatThrownBy(() -> dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, sources, cards))
                .isInstanceOf(IllegalStateException.class);
        // SAVED record면 이벤트를 하나도 저장하지 않는다.
        verify(timelineEventService, never()).save(any());
    }

    @Test
    void appendDailyTimeline_rejectsEmptyItemIds() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, ItemType.PHOTO, t, null, "summary-0", new PhotoPayload("uri", 1.0, 2.0)));
        List<CardSuggestionDto> cards = List.of(
                new CardSuggestionDto("아침", "산책", t, t.plusHours(1), List.of()));

        assertThatThrownBy(() -> dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, sources, cards))
                .isInstanceOf(IllegalArgumentException.class);
        // 검증은 record 생성 전에 끝나므로, 잘못된 콜백은 daily record를 만들지 않는다(고아 빈 DRAFT 방지).
        verify(dailyRecordService, never()).findOrCreateDraft(any(), any());
        verify(timelineEventService, never()).save(any());
    }

    @Test
    void appendDailyTimeline_rejectsUnknownItemId() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(0, ItemType.PHOTO, t, null, "summary-0", new PhotoPayload("uri", 1.0, 2.0)));
        // 이벤트가 sourceItems에 없는 itemId 1을 참조한다.
        List<CardSuggestionDto> cards = List.of(
                new CardSuggestionDto("아침", "산책", t, t.plusHours(1), List.of(1)));

        assertThatThrownBy(() -> dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, sources, cards))
                .isInstanceOf(IllegalArgumentException.class);
        // record 생성·이벤트 저장 전에 검증 실패 → 고아 빈 DRAFT 없음.
        verify(dailyRecordService, never()).findOrCreateDraft(any(), any());
        verify(timelineEventService, never()).save(any());
    }

    // --- getDailyTimeline (읽기) ---

    @Test
    void getDailyTimeline_assemblesRecordEventsAndItems() {
        DailyRecord record = DailyRecord.createDraft(USER_ID, RECORD_DATE);
        ReflectionTestUtils.setField(record, "dailyRecordId", 300L);
        ReflectionTestUtils.setField(record, "emotionType", EmotionType.HAPPY);
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(record));

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 10, 0);
        TimelineEvent event = TimelineEvent.of(300L, t, t.plusHours(2), "제목", "부제목");
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        ReflectionTestUtils.setField(event, "memo", "내 메모");
        when(timelineEventService.findByDailyRecordId(300L)).thenReturn(List.of(event));

        PhotoPayload photo = new PhotoPayload("uri", 1.0, 2.0);
        LocationPayload location = new LocationPayload("카페", "강남", 3.0, 4.0);
        TimelineItem item0 = TimelineItem.of(11L, ItemType.PHOTO, t, null, objectMapper.valueToTree(photo));
        ReflectionTestUtils.setField(item0, "timelineItemId", 21L);
        TimelineItem item1 = TimelineItem.of(11L, ItemType.LOCATION, t.plusHours(1), t.plusHours(2),
                objectMapper.valueToTree(location));
        ReflectionTestUtils.setField(item1, "timelineItemId", 22L);
        when(timelineItemService.findByTimelineEventId(11L)).thenReturn(List.of(item0, item1));

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

        TimelineItemResponse itemResponse0 = eventResponse.items().get(0);
        assertThat(itemResponse0.timelineItemId()).isEqualTo(21L);
        assertThat(itemResponse0.itemType()).isEqualTo(ItemType.PHOTO);
        assertThat(itemResponse0.startAt()).isEqualTo(t);
        assertThat(itemResponse0.endAt()).isNull();
        // payload는 이제 raw JsonNode(타입 정보 없음).
        assertThat(itemResponse0.payload().get("photoUri").asText()).isEqualTo("uri");
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
