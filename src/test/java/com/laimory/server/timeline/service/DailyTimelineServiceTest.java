package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.StayPayload;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 하루 타임라인 읽기 오케스트레이터가 junction 경유로 leaf 서비스를 올바르게 합성하는지 단위 검증. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class DailyTimelineServiceTest {

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private TimelineEventItemService timelineEventItemService;
    @Mock
    private TimelineItemService timelineItemService;

    @InjectMocks
    private DailyTimelineService dailyTimelineService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Long USER_ID = 7L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 6, 17);
    private static final LocalDateTime RECORD_AT = LocalDateTime.of(2026, 6, 17, 12, 0);
    private static final String ZONE = "Asia/Seoul";

    private TimelineItem item(long id, ItemType type, String rawId, LocalDateTime startAt, Object payload) {
        TimelineItem item = TimelineItem.of(type, rawId, startAt, null, MAPPER.valueToTree(payload));
        ReflectionTestUtils.setField(item, "timelineItemId", id);
        return item;
    }

    @Test
    void getDailyTimeline_assemblesRecordEventsAndItemsViaJunction() {
        DailyRecord record = DailyRecord.createDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(record, "dailyRecordId", 300L);
        ReflectionTestUtils.setField(record, "emotionType", EmotionType.HAPPY);
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(record));

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 10, 0);
        TimelineEvent event = TimelineEvent.of(300L, TimelineEventType.EXERCISE, t, t.plusHours(2), "제목", "부제목");
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        ReflectionTestUtils.setField(event, "memo", "내 메모");
        when(timelineEventService.findByDailyRecordId(300L)).thenReturn(List.of(event));

        // junction이 (22, 21) 순서로 와도 응답은 startAt·id 오름차순으로 정렬된다.
        when(timelineEventItemService.findByTimelineEventIds(List.of(11L)))
                .thenReturn(List.of(TimelineEventItem.of(11L, 22L), TimelineEventItem.of(11L, 21L)));
        StayPayload stay = new StayPayload(3.0, 4.0, "서울 성동구 왕십리로 83-21", List.of("카페"), null);
        TimelineItem photoItem = item(21L, ItemType.PHOTO, "raw-21", t,
                new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://x",
                        1.0, 2.0, null, "https://cdn.example/x"));
        TimelineItem stayItem = TimelineItem.of(ItemType.STAY, "raw-22", t.plusHours(1), t.plusHours(2),
                MAPPER.valueToTree(stay));
        ReflectionTestUtils.setField(stayItem, "timelineItemId", 22L);
        when(timelineItemService.findByIds(List.of(22L, 21L))).thenReturn(List.of(stayItem, photoItem));

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

        // 정렬: startAt 오름차순(21 → 22). payload는 저장본 그대로 통과한다 — PHOTO의 photoUrl도
        // 저장 시 주입된 값(읽기 시점 변환 없음).
        TimelineItemResponse itemResponse0 = eventResponse.items().get(0);
        assertThat(itemResponse0.timelineItemId()).isEqualTo(21L);
        assertThat(itemResponse0.itemType()).isEqualTo(ItemType.PHOTO);
        assertThat(itemResponse0.rawId()).isEqualTo("raw-21");
        assertThat(itemResponse0.startAt()).isEqualTo(t);
        assertThat(itemResponse0.endAt()).isNull();
        assertThat(itemResponse0.payload().get("photoUrl").asText()).isEqualTo("https://cdn.example/x");
        assertThat(itemResponse0.payload().get("clientPhotoUri").asText()).isEqualTo("content://x");
        assertThat(itemResponse0.payload().has("itemType")).isFalse();

        TimelineItemResponse itemResponse1 = eventResponse.items().get(1);
        assertThat(itemResponse1.timelineItemId()).isEqualTo(22L);
        assertThat(itemResponse1.itemType()).isEqualTo(ItemType.STAY);
        assertThat(itemResponse1.payload().get("address").asText()).isEqualTo("서울 성동구 왕십리로 83-21");
    }

    @Test
    void getDailyTimeline_sharedItemAppearsInEveryLinkedEvent() {
        // N:M: 같은 Item(21)이 두 Event에 연결되면 두 Event의 items에 모두 나타난다(같은 timelineItemId 반복 —
        // Android 수용 확인된 계약).
        DailyRecord record = DailyRecord.createDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(record, "dailyRecordId", 300L);
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(record));

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 10, 0);
        TimelineEvent eventA = TimelineEvent.of(300L, TimelineEventType.UNKNOWN, t, null, "A", null);
        ReflectionTestUtils.setField(eventA, "timelineEventId", 11L);
        TimelineEvent eventB = TimelineEvent.of(300L, TimelineEventType.UNKNOWN, t.plusHours(1), null, "B", null);
        ReflectionTestUtils.setField(eventB, "timelineEventId", 12L);
        when(timelineEventService.findByDailyRecordId(300L)).thenReturn(List.of(eventA, eventB));

        when(timelineEventItemService.findByTimelineEventIds(List.of(11L, 12L)))
                .thenReturn(List.of(TimelineEventItem.of(11L, 21L), TimelineEventItem.of(12L, 21L)));
        TimelineItem shared = item(21L, ItemType.PHOTO, "raw-21", t,
                new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://x", 1.0, 2.0, null, null));
        when(timelineItemService.findByIds(List.of(21L))).thenReturn(List.of(shared));

        DailyTimelineResponse result = dailyTimelineService.getDailyTimeline(300L);

        assertThat(result.events()).hasSize(2);
        assertThat(result.events().get(0).items()).extracting(TimelineItemResponse::timelineItemId)
                .containsExactly(21L);
        assertThat(result.events().get(1).items()).extracting(TimelineItemResponse::timelineItemId)
                .containsExactly(21L);
    }

    @Test
    void getDailyTimeline_nullStartAtItemsSortFirst() {
        // 기존 SQL 정렬(MySQL ASC NULLS-FIRST) 동작 보존: startAt null인 Item이 앞에 온다.
        DailyRecord record = DailyRecord.createDraft(USER_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(record, "dailyRecordId", 300L);
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(record));

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 10, 0);
        TimelineEvent event = TimelineEvent.of(300L, TimelineEventType.UNKNOWN, t, null, "A", null);
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        when(timelineEventService.findByDailyRecordId(300L)).thenReturn(List.of(event));

        when(timelineEventItemService.findByTimelineEventIds(List.of(11L)))
                .thenReturn(List.of(TimelineEventItem.of(11L, 21L), TimelineEventItem.of(11L, 22L)));
        TimelineItem timed = item(21L, ItemType.PHOTO, "raw-21", t,
                new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://x", 1.0, 2.0, null, null));
        TimelineItem untimed = item(22L, ItemType.NOTIFICATION, "raw-22", null,
                MAPPER.createObjectNode().put("title", "n"));
        when(timelineItemService.findByIds(List.of(21L, 22L))).thenReturn(List.of(timed, untimed));

        DailyTimelineResponse result = dailyTimelineService.getDailyTimeline(300L);

        assertThat(result.events().get(0).items()).extracting(TimelineItemResponse::timelineItemId)
                .containsExactly(22L, 21L);
    }

    @Test
    void getDailyTimeline_throwsWhenRecordNotFound() {
        when(dailyRecordService.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dailyTimelineService.getDailyTimeline(999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("999");
    }
}
