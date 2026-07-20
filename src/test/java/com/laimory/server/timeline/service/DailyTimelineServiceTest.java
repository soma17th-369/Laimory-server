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
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineEventSuggestionDto;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.StayPayload;
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
    private TimelineDraftEventSuggestionService timelineDraftEventSuggestionService;
    @Mock
    private TimelineEventSuggestionValidator timelineEventSuggestionValidator;

    @InjectMocks
    private DailyTimelineService dailyTimelineService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Long USER_ID = 7L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 6, 17);
    private static final LocalDateTime RECORD_AT = LocalDateTime.of(2026, 6, 17, 12, 0);
    private static final String ZONE = "Asia/Seoul";
    private static final String TASK_ID = "task-1";

    private TimelineDraftSourceItem photoRow(long pk, LocalDateTime startAt) {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of(TASK_ID, USER_ID, ItemType.PHOTO, "raw-" + pk,
                startAt, null,
                MAPPER.valueToTree(new PhotoPayload("uri" + pk, "content://" + pk, 1.0, 2.0, null, null)));
        ReflectionTestUtils.setField(row, "timelineDraftSourceItemId", pk);
        return row;
    }

    private TimelineDraftSourceItem stayRow(long pk, LocalDateTime startAt) {
        TimelineDraftSourceItem row = TimelineDraftSourceItem.of(TASK_ID, USER_ID, ItemType.STAY, "raw-" + pk,
                startAt, null,
                MAPPER.valueToTree(new StayPayload(3.0, 4.0, null, null, null)));
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
                new TimelineEventSuggestionDto(TimelineEventType.MEAL, "아침", "산책", t, t.plusHours(1), List.of(10L)));

        Long result = dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, RECORD_AT, ZONE, draftRows, events);

        assertThat(result).isEqualTo(100L);
        verify(timelineEventSuggestionValidator).validate(draftRows, events);

        // suggestion의 eventType이 저장되는 TimelineEvent로 그대로 복사된다(재추론 없음).
        ArgumentCaptor<TimelineEvent> eventCaptor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timelineEventService, times(1)).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType()).isEqualTo(TimelineEventType.MEAL);

        // 아이템은 draft 행에서 그대로 복사된다(itemType/rawId/start/payload).
        ArgumentCaptor<TimelineItem> itemCaptor = ArgumentCaptor.forClass(TimelineItem.class);
        verify(timelineItemService, times(1)).save(itemCaptor.capture());
        TimelineItem saved = itemCaptor.getValue();
        assertThat(saved.getItemType()).isEqualTo(ItemType.PHOTO);
        assertThat(saved.getRawId()).isEqualTo("raw-10");
        assertThat(saved.getStartAt()).isEqualTo(t);
        assertThat(saved.getPayload().get("filename").asText()).isEqualTo("uri10");

        // 소비한 staging 행을 taskId로 삭제한다(같은 트랜잭션): source item + event suggestion 둘 다.
        verify(timelineDraftSourceItemService).deleteByTaskId(TASK_ID);
        verify(timelineDraftEventSuggestionService).deleteByTaskId(TASK_ID);
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
                stayRow(11, t.plusHours(1)),
                photoRow(12, t.plusHours(2)));
        // 이벤트 A: item PK 10,12 / 이벤트 B: item PK 11
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, "A", "subA", t, t.plusHours(2), List.of(10L, 12L)),
                new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, "B", "subB", t.plusHours(1), null, List.of(11L)));

        Long result = dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, RECORD_AT, ZONE, draftRows, events);

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
                new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, "아침", "산책", t, t.plusHours(1), List.of(10L)));

        assertThatThrownBy(() -> dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, RECORD_AT, ZONE, draftRows, events))
                .isInstanceOf(IllegalStateException.class);
        // SAVED record면 이벤트를 하나도 저장하지 않고 staging도 삭제하지 않는다(롤백 대상).
        verify(timelineEventService, never()).save(any());
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
        verify(timelineDraftEventSuggestionService, never()).deleteByTaskId(anyString());
    }

    @Test
    void appendDailyTimeline_validationFails_doesNotPersistOrDeleteDrafts() {
        // 검증기가 위반을 던지면(record 생성 전 첫 단계) 영속·삭제가 일어나지 않는다(전체 롤백).
        doThrow(new IllegalArgumentException("event references unknown itemId: 1"))
                .when(timelineEventSuggestionValidator).validate(any(), any());

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftSourceItem> draftRows = List.of(photoRow(10, t));
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, "아침", "산책", t, t.plusHours(1), List.of(99L)));

        assertThatThrownBy(() -> dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, RECORD_AT, ZONE, draftRows, events))
                .isInstanceOf(IllegalArgumentException.class);
        verify(dailyRecordService, never()).findOrCreateDraft(any(), any(), any(), any());
        verify(timelineEventService, never()).save(any());
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
        verify(timelineDraftEventSuggestionService, never()).deleteByTaskId(anyString());
    }

    @Test
    void appendDailyTimeline_nudgesNewEventStartAtWhenCollidesWithExistingEvent() {
        DailyRecord existing = DailyRecord.createDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(existing, "dailyRecordId", 100L);
        when(dailyRecordService.findOrCreateDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE)).thenReturn(existing);
        stubEventSaveWithSequentialIds();
        // 기존(동결) event가 18:30 시작 → 새 event도 18:30이면 +10분 밀린다.
        LocalDateTime t1830 = LocalDateTime.of(2026, 6, 17, 18, 30);
        TimelineEvent frozen = TimelineEvent.of(100L, TimelineEventType.UNKNOWN, t1830, null, "기존", null);
        ReflectionTestUtils.setField(frozen, "timelineEventId", 5L);
        when(timelineEventService.findByDailyRecordId(100L)).thenReturn(List.of(frozen));

        List<TimelineDraftSourceItem> draftRows = List.of(photoRow(10, t1830));
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, "새", null, t1830, t1830.plusMinutes(5), List.of(10L)));

        dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, RECORD_AT, ZONE, draftRows, events);

        ArgumentCaptor<TimelineEvent> eventCaptor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timelineEventService).save(eventCaptor.capture());
        TimelineEvent saved = eventCaptor.getValue();
        assertThat(saved.getStartAt()).isEqualTo(t1830.plusMinutes(10));
        // endAt(18:35)이 nudge된 startAt(18:40)보다 앞 → startAt으로 클램프.
        assertThat(saved.getEndAt()).isEqualTo(t1830.plusMinutes(10));
    }

    @Test
    void appendDailyTimeline_noStartAtCollision_keepsOriginalStartAtAndEndAt() {
        DailyRecord existing = DailyRecord.createDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(existing, "dailyRecordId", 100L);
        when(dailyRecordService.findOrCreateDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE)).thenReturn(existing);
        stubEventSaveWithSequentialIds();
        TimelineEvent frozen = TimelineEvent.of(100L, TimelineEventType.UNKNOWN, LocalDateTime.of(2026, 6, 17, 18, 0), null, "기존", null);
        ReflectionTestUtils.setField(frozen, "timelineEventId", 5L);
        when(timelineEventService.findByDailyRecordId(100L)).thenReturn(List.of(frozen));

        LocalDateTime t9 = LocalDateTime.of(2026, 6, 17, 9, 0);
        List<TimelineDraftSourceItem> draftRows = List.of(photoRow(10, t9));
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, "새", null, t9, t9.plusHours(1), List.of(10L)));

        dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, RECORD_AT, ZONE, draftRows, events);

        ArgumentCaptor<TimelineEvent> eventCaptor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timelineEventService).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStartAt()).isEqualTo(t9);
        assertThat(eventCaptor.getValue().getEndAt()).isEqualTo(t9.plusHours(1));
    }

    @Test
    void appendDailyTimeline_nudgesCollidingNewEventsWithinSameBatch() {
        DailyRecord created = DailyRecord.createDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(created, "dailyRecordId", 200L);
        when(dailyRecordService.findOrCreateDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE)).thenReturn(created);
        stubEventSaveWithSequentialIds();
        // 기존 event 없음(그날 첫 append). 같은 12:00 시작 새 event 두 개 → 둘째는 12:10으로 밀린다.
        LocalDateTime t12 = LocalDateTime.of(2026, 6, 17, 12, 0);
        List<TimelineDraftSourceItem> draftRows = List.of(photoRow(10, t12), photoRow(11, t12));
        List<TimelineEventSuggestionDto> events = List.of(
                new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, "A", null, t12, null, List.of(10L)),
                new TimelineEventSuggestionDto(TimelineEventType.UNKNOWN, "B", null, t12, null, List.of(11L)));

        dailyTimelineService.appendDailyTimeline(USER_ID, RECORD_DATE, RECORD_AT, ZONE, draftRows, events);

        ArgumentCaptor<TimelineEvent> eventCaptor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timelineEventService, times(2)).save(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues()).extracting(TimelineEvent::getStartAt)
                .containsExactly(t12, t12.plusMinutes(10));
    }

    // --- getDailyTimeline (읽기) ---

    @Test
    void getDailyTimeline_assemblesRecordEventsAndItems() {
        DailyRecord record = DailyRecord.createDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(record, "dailyRecordId", 300L);
        ReflectionTestUtils.setField(record, "emotionType", EmotionType.HAPPY);
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(record));

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 10, 0);
        TimelineEvent event = TimelineEvent.of(300L, TimelineEventType.EXERCISE, t, t.plusHours(2), "제목", "부제목");
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        ReflectionTestUtils.setField(event, "memo", "내 메모");
        when(timelineEventService.findByDailyRecordId(300L)).thenReturn(List.of(event));

        StayPayload stay = new StayPayload(3.0, 4.0, "서울 성동구 왕십리로 83-21", List.of("카페"), null);
        TimelineItem item0 = TimelineItem.of(11L, ItemType.PHOTO, "raw-21", t, null,
                MAPPER.valueToTree(new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://x",
                        1.0, 2.0, null, "https://cdn.example/x")));
        ReflectionTestUtils.setField(item0, "timelineItemId", 21L);
        TimelineItem item1 = TimelineItem.of(11L, ItemType.STAY, "raw-22", t.plusHours(1), t.plusHours(2),
                MAPPER.valueToTree(stay));
        ReflectionTestUtils.setField(item1, "timelineItemId", 22L);
        when(timelineItemService.findByTimelineEventId(11L)).thenReturn(List.of(item0, item1));

        DailyTimelineResponse result = dailyTimelineService.getDailyTimeline(300L);

        assertThat(result.recordDate()).isEqualTo(RECORD_DATE);
        assertThat(result.emotionType()).isEqualTo(EmotionType.HAPPY);
        assertThat(result.events()).hasSize(1);

        TimelineEventResponse eventResponse = result.events().get(0);
        assertThat(eventResponse.timelineEventId()).isEqualTo(11L);
        assertThat(eventResponse.eventType()).isEqualTo(TimelineEventType.EXERCISE);
        assertThat(eventResponse.startAt()).isEqualTo(t);
        assertThat(eventResponse.endAt()).isEqualTo(t.plusHours(2));
        assertThat(eventResponse.title()).isEqualTo("제목");
        assertThat(eventResponse.subtitle()).isEqualTo("부제목");
        assertThat(eventResponse.memo()).isEqualTo("내 메모");
        assertThat(eventResponse.items()).hasSize(2);

        // payload는 저장본 그대로 통과한다 — PHOTO의 photoUrl도 저장 시 주입된 값(읽기 시점 변환 없음).
        TimelineItemResponse itemResponse0 = eventResponse.items().get(0);
        assertThat(itemResponse0.timelineItemId()).isEqualTo(21L);
        assertThat(itemResponse0.itemType()).isEqualTo(ItemType.PHOTO);
        assertThat(itemResponse0.rawId()).isEqualTo("raw-21");
        assertThat(itemResponse0.startAt()).isEqualTo(t);
        assertThat(itemResponse0.endAt()).isNull();
        assertThat(itemResponse0.payload().get("photoUrl").asText()).isEqualTo("https://cdn.example/x");
        assertThat(itemResponse0.payload().get("clientPhotoUri").asText()).isEqualTo("content://x");
        assertThat(itemResponse0.payload().get("filename").asText())
                .isEqualTo("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg");
        assertThat(itemResponse0.payload().has("itemType")).isFalse();

        TimelineItemResponse itemResponse1 = eventResponse.items().get(1);
        assertThat(itemResponse1.timelineItemId()).isEqualTo(22L);
        assertThat(itemResponse1.itemType()).isEqualTo(ItemType.STAY);
        assertThat(itemResponse1.payload().get("address").asText()).isEqualTo("서울 성동구 왕십리로 83-21");
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
