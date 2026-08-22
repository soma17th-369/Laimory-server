package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DailyTimelinesResponse;
import com.laimory.server.timeline.dto.MonthlyDailyRecordResponse;
import com.laimory.server.timeline.dto.MonthlyDailyRecordListResponse;
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
import java.util.UUID;
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
    private static final java.util.UUID SUBJECT_ID =
            com.laimory.server.testsupport.TestSubjects.id(7L);
    private static final java.util.UUID OTHER_SUBJECT_ID =
            com.laimory.server.testsupport.TestSubjects.id(999L);
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 6, 17);
    private static final LocalDateTime RECORD_AT = LocalDateTime.of(2026, 6, 17, 12, 0);
    private static final String ZONE = "Asia/Seoul";

    private TimelineItem item(long id, ItemType type, String rawId, LocalDateTime startAt, Object payload) {
        TimelineItem item = TimelineItem.of(type, rawId, startAt, null, MAPPER.valueToTree(payload));
        ReflectionTestUtils.setField(item, "timelineItemId", id);
        return item;
    }

    private DailyRecord record(long id, LocalDate recordDate) {
        DailyRecord record = DailyRecord.createDraft(SUBJECT_ID, recordDate, recordDate.atTime(12, 0), ZONE);
        ReflectionTestUtils.setField(record, "dailyRecordId", id);
        return record;
    }

    @Test
    void getDailyTimeline_ownedRecord_assemblesEventsAndItemsViaJunction() {
        DailyRecord record = DailyRecord.createDraft(SUBJECT_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(record, "dailyRecordId", 300L);
        ReflectionTestUtils.setField(record, "emotionType", EmotionType.HAPPY);
        when(dailyRecordService.findByDailyRecordIdAndSubjectId(300L, SUBJECT_ID)).thenReturn(Optional.of(record));

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 10, 0);
        TimelineEvent event = TimelineEvent.of(300L, TimelineEventType.EXERCISE, t, t.plusHours(2),
                "제목", "부제목", "오늘 운동은 어땠나요?", "한강공원", "서울특별시 영등포구 여의동로 330");
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        ReflectionTestUtils.setField(event, "memo", "내 메모");
        when(timelineEventService.findByDailyRecordIds(List.of(300L))).thenReturn(List.of(event));

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

        DailyTimelineResponse result = dailyTimelineService.getDailyTimeline("v1", SUBJECT_ID, 300L);

        assertThat(result.recordDate()).isEqualTo(RECORD_DATE);
        assertThat(result.status()).isEqualTo(DailyRecordStatus.DRAFT);
        assertThat(result.emotionType()).isEqualTo(EmotionType.HAPPY);
        assertThat(result.events()).hasSize(1);

        TimelineEventResponse eventResponse = result.events().get(0);
        assertThat(eventResponse.timelineEventId()).isEqualTo(11L);
        assertThat(eventResponse.eventType()).isEqualTo(TimelineEventType.EXERCISE);
        assertThat(eventResponse.startAt()).isEqualTo(t);
        assertThat(eventResponse.endAt()).isEqualTo(t.plusHours(2));
        assertThat(eventResponse.title()).isEqualTo("제목");
        assertThat(eventResponse.subtitle()).isEqualTo("부제목");
        assertThat(eventResponse.question()).isEqualTo("오늘 운동은 어땠나요?");
        assertThat(eventResponse.place()).isEqualTo("한강공원");
        assertThat(eventResponse.address()).isEqualTo("서울특별시 영등포구 여의동로 330");
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
    void getDailyTimeline_ownedDate_returnsRecordGraph() {
        DailyRecord record = record(300L, RECORD_DATE);
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.of(record));
        when(timelineEventService.findByDailyRecordIds(List.of(300L))).thenReturn(List.of());
        when(timelineEventItemService.findByTimelineEventIds(List.of())).thenReturn(List.of());
        when(timelineItemService.findByIds(List.of())).thenReturn(List.of());

        DailyTimelineResponse result = dailyTimelineService.getDailyTimeline("v1", SUBJECT_ID, RECORD_DATE);

        assertThat(result.dailyRecordId()).isEqualTo(300L);
        assertThat(result.recordDate()).isEqualTo(RECORD_DATE);
        assertThat(result.events()).isEmpty();
        verify(dailyRecordService).findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE);
    }

    @Test
    void getDailyTimeline_dateMissing_throwsDailyRecordNotFoundWithoutLoadingGraph() {
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> dailyTimelineService.getDailyTimeline("v1", SUBJECT_ID, RECORD_DATE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND));

        verifyNoInteractions(timelineEventService, timelineEventItemService, timelineItemService);
    }

    @Test
    void getTimelineEvent_ownedDraft_assemblesItemsWithNullFirstTimeAndIdOrdering() {
        DailyRecord record = record(300L, RECORD_DATE);
        LocalDateTime eventStart = RECORD_DATE.atTime(9, 0);
        TimelineEvent event = TimelineEvent.of(
                300L, TimelineEventType.WORK, eventStart, eventStart.plusHours(2), "업무", "오전",
                "오전 업무 중 가장 집중된 순간은 언제였나요?", "성수 오피스", "서울특별시 성동구 아차산로 17");
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        ReflectionTestUtils.setField(event, "memo", "중요 메모");
        when(timelineEventService.findById(11L)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(record));

        // junction/Item 반환 순서와 무관하게 null startAt이 먼저, 동시간은 ID 오름차순이다.
        when(timelineEventItemService.findByTimelineEventIds(List.of(11L)))
                .thenReturn(List.of(
                        TimelineEventItem.of(11L, 22L),
                        TimelineEventItem.of(11L, 23L),
                        TimelineEventItem.of(11L, 21L)));
        LocalDateTime itemStart = RECORD_DATE.atTime(10, 0);
        TimelineItem laterId = item(22L, ItemType.NOTIFICATION, "raw-22", itemStart,
                MAPPER.createObjectNode().put("title", "later-id"));
        TimelineItem untimed = item(23L, ItemType.NOTIFICATION, "raw-23", null,
                MAPPER.createObjectNode().put("title", "untimed"));
        TimelineItem earlierId = item(21L, ItemType.NOTIFICATION, "raw-21", itemStart,
                MAPPER.createObjectNode().put("title", "earlier-id"));
        when(timelineItemService.findByIds(List.of(22L, 23L, 21L)))
                .thenReturn(List.of(laterId, untimed, earlierId));

        TimelineEventResponse result = dailyTimelineService.getTimelineEvent("v1", SUBJECT_ID, 11L);

        assertThat(result.timelineEventId()).isEqualTo(11L);
        assertThat(result.eventType()).isEqualTo(TimelineEventType.WORK);
        assertThat(result.startAt()).isEqualTo(eventStart);
        assertThat(result.endAt()).isEqualTo(eventStart.plusHours(2));
        assertThat(result.title()).isEqualTo("업무");
        assertThat(result.subtitle()).isEqualTo("오전");
        assertThat(result.question()).isEqualTo("오전 업무 중 가장 집중된 순간은 언제였나요?");
        assertThat(result.place()).isEqualTo("성수 오피스");
        assertThat(result.address()).isEqualTo("서울특별시 성동구 아차산로 17");
        assertThat(result.memo()).isEqualTo("중요 메모");
        assertThat(result.items()).extracting(TimelineItemResponse::timelineItemId)
                .containsExactly(23L, 21L, 22L);
        assertThat(result.items().get(0).payload().get("title").asText()).isEqualTo("untimed");
    }

    @Test
    void getTimelineEvent_ownedSavedRecord_isReadableWithEmptyItems() {
        DailyRecord saved = record(300L, RECORD_DATE);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        TimelineEvent event = TimelineEvent.of(
                300L, TimelineEventType.REST, RECORD_DATE.atTime(13, 0), null, "휴식", null, null, null, null);
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        when(timelineEventService.findById(11L)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(saved));
        when(timelineEventItemService.findByTimelineEventIds(List.of(11L))).thenReturn(List.of());
        when(timelineItemService.findByIds(List.of())).thenReturn(List.of());

        TimelineEventResponse result = dailyTimelineService.getTimelineEvent("v1", SUBJECT_ID, 11L);

        assertThat(result.timelineEventId()).isEqualTo(11L);
        assertThat(result.items()).isEmpty();
    }

    @Test
    void getTimelineEvent_missingEvent_throwsEventNotFoundWithoutLoadingParentOrItems() {
        when(timelineEventService.findById(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dailyTimelineService.getTimelineEvent("v1", SUBJECT_ID, 11L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND));

        verifyNoInteractions(dailyRecordService, timelineEventItemService, timelineItemService);
    }

    @Test
    void getTimelineEvent_parentMissing_throwsEventNotFoundWithoutLoadingItems() {
        TimelineEvent event = TimelineEvent.of(
                300L, TimelineEventType.UNKNOWN, RECORD_DATE.atTime(9, 0), null, "이벤트", null, null, null, null);
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        when(timelineEventService.findById(11L)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(300L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dailyTimelineService.getTimelineEvent("v1", SUBJECT_ID, 11L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND));

        verifyNoInteractions(timelineEventItemService, timelineItemService);
    }

    @Test
    void getTimelineEvent_foreignParent_throwsEventNotFoundWithoutLoadingItems() {
        TimelineEvent event = TimelineEvent.of(
                300L, TimelineEventType.UNKNOWN, RECORD_DATE.atTime(9, 0), null, "이벤트", null, null, null, null);
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        DailyRecord foreign = DailyRecord.createDraft(
                OTHER_SUBJECT_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(foreign, "dailyRecordId", 300L);
        when(timelineEventService.findById(11L)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> dailyTimelineService.getTimelineEvent("v1", SUBJECT_ID, 11L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND));

        verifyNoInteractions(timelineEventItemService, timelineItemService);
    }

    @Test
    void getDailyTimelines_preservesRecordOrderAndLoadsWholeGraphInBulk() {
        DailyRecord recent = record(301L, RECORD_DATE.plusDays(1));
        DailyRecord saved = record(300L, RECORD_DATE);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        DailyRecord empty = record(299L, RECORD_DATE.minusDays(1));
        when(dailyRecordService.findBySubjectIdOrderByRecordDateDescDailyRecordIdDesc(SUBJECT_ID))
                .thenReturn(List.of(recent, saved, empty));

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 10, 0);
        TimelineEvent savedEvent = TimelineEvent.of(300L, TimelineEventType.WORK, t, null, "saved", null,
                null, null, null);
        ReflectionTestUtils.setField(savedEvent, "timelineEventId", 11L);
        TimelineEvent recentEvent = TimelineEvent.of(301L, TimelineEventType.REST, t.plusDays(1), null,
                "recent", null, null, null, null);
        ReflectionTestUtils.setField(recentEvent, "timelineEventId", 12L);
        when(timelineEventService.findByDailyRecordIds(List.of(301L, 300L, 299L)))
                .thenReturn(List.of(savedEvent, recentEvent));
        when(timelineEventItemService.findByTimelineEventIds(List.of(11L, 12L))).thenReturn(List.of());
        when(timelineItemService.findByIds(List.of())).thenReturn(List.of());

        DailyTimelinesResponse result = dailyTimelineService.getDailyTimelines("v1", SUBJECT_ID);

        assertThat(result.timelines()).extracting(DailyTimelineResponse::dailyRecordId)
                .containsExactly(301L, 300L, 299L);
        // DRAFT/SAVED 혼합 목록에서 각 record의 상태가 그대로 보존된다(#298).
        assertThat(result.timelines()).extracting(DailyTimelineResponse::status)
                .containsExactly(DailyRecordStatus.DRAFT, DailyRecordStatus.SAVED, DailyRecordStatus.DRAFT);
        assertThat(result.timelines().get(0).events()).extracting(TimelineEventResponse::timelineEventId)
                .containsExactly(12L);
        assertThat(result.timelines().get(1).events()).extracting(TimelineEventResponse::timelineEventId)
                .containsExactly(11L);
        assertThat(result.timelines().get(2).events()).isEmpty();
        verify(timelineEventService).findByDailyRecordIds(List.of(301L, 300L, 299L));
        verify(timelineEventItemService).findByTimelineEventIds(List.of(11L, 12L));
        verify(timelineItemService).findByIds(List.of());
    }

    @Test
    void getDailyTimelines_noRecords_returnsEmptyWithoutLoadingGraph() {
        when(dailyRecordService.findBySubjectIdOrderByRecordDateDescDailyRecordIdDesc(SUBJECT_ID))
                .thenReturn(List.of());

        DailyTimelinesResponse result = dailyTimelineService.getDailyTimelines("v1", SUBJECT_ID);

        assertThat(result.timelines()).isEmpty();
        verifyNoInteractions(timelineEventService, timelineEventItemService, timelineItemService);
    }

    @Test
    void getDailyTimeline_sharedItemAppearsInEveryLinkedEvent() {
        // N:M: 같은 Item(21)이 두 Event에 연결되면 두 Event의 items에 모두 나타난다(같은 timelineItemId 반복 —
        // Android 수용 확인된 계약).
        DailyRecord record = DailyRecord.createDraft(SUBJECT_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(record, "dailyRecordId", 300L);
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(record));

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 10, 0);
        TimelineEvent eventA = TimelineEvent.of(300L, TimelineEventType.UNKNOWN, t, null, "A", null, null, null, null);
        ReflectionTestUtils.setField(eventA, "timelineEventId", 11L);
        TimelineEvent eventB = TimelineEvent.of(300L, TimelineEventType.UNKNOWN, t.plusHours(1), null, "B",
                null, null, null, null);
        ReflectionTestUtils.setField(eventB, "timelineEventId", 12L);
        when(timelineEventService.findByDailyRecordIds(List.of(300L))).thenReturn(List.of(eventA, eventB));

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
        DailyRecord record = DailyRecord.createDraft(SUBJECT_ID, RECORD_DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(record, "dailyRecordId", 300L);
        when(dailyRecordService.findById(300L)).thenReturn(Optional.of(record));

        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 10, 0);
        TimelineEvent event = TimelineEvent.of(300L, TimelineEventType.UNKNOWN, t, null, "A", null, null, null, null);
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        when(timelineEventService.findByDailyRecordIds(List.of(300L))).thenReturn(List.of(event));

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

    // --- getMonthlyDailyRecords (캘린더 월별 경량 조회) ---

    @Test
    void getMonthlyDailyRecords_mapsDateAndEmotionOnlyWithoutLoadingGraph() {
        DailyRecord withEmotion = record(300L, LocalDate.of(2026, 5, 3));
        ReflectionTestUtils.setField(withEmotion, "status", DailyRecordStatus.SAVED);
        ReflectionTestUtils.setField(withEmotion, "emotionType", EmotionType.HAPPY);
        DailyRecord draftWithoutEmotion = record(301L, LocalDate.of(2026, 5, 19));
        when(dailyRecordService.findBySubjectIdAndRecordDateBetweenOrderByRecordDateAsc(
                SUBJECT_ID, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)))
                .thenReturn(List.of(withEmotion, draftWithoutEmotion));

        MonthlyDailyRecordListResponse result =
                dailyTimelineService.getMonthlyDailyRecords("v1", SUBJECT_ID, 2026, 5);

        // 캘린더 read model은 recordDate·nullable emotionType만 담는다 — DRAFT/SAVED 모두 포함.
        assertThat(result.dailyRecords()).containsExactly(
                new MonthlyDailyRecordResponse(LocalDate.of(2026, 5, 3), EmotionType.HAPPY),
                new MonthlyDailyRecordResponse(LocalDate.of(2026, 5, 19), null));
        // Event·junction·Item leaf 서비스는 호출하지 않는다(경량 조회).
        verifyNoInteractions(timelineEventService, timelineEventItemService, timelineItemService);
    }

    @Test
    void getMonthlyDailyRecords_usesInclusiveMonthBoundsFromYearMonth() {
        when(dailyRecordService.findBySubjectIdAndRecordDateBetweenOrderByRecordDateAsc(
                SUBJECT_ID, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
                .thenReturn(List.of());

        dailyTimelineService.getMonthlyDailyRecords("v1", SUBJECT_ID, 2026, 2);

        // 월 첫날 이상·마지막 날 이하 — 윤년이 아닌 2월은 28일까지다.
        verify(dailyRecordService).findBySubjectIdAndRecordDateBetweenOrderByRecordDateAsc(
                SUBJECT_ID, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));
    }

    @Test
    void getMonthlyDailyRecords_emptyMonth_returnsEmptyArray() {
        when(dailyRecordService.findBySubjectIdAndRecordDateBetweenOrderByRecordDateAsc(
                SUBJECT_ID, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .thenReturn(List.of());

        MonthlyDailyRecordListResponse result =
                dailyTimelineService.getMonthlyDailyRecords("v1", SUBJECT_ID, 2026, 6);

        assertThat(result.dailyRecords()).isEmpty();
    }

    @Test
    void getMonthlyDailyRecords_acceptsSupportedRangeEdges() {
        when(dailyRecordService.findBySubjectIdAndRecordDateBetweenOrderByRecordDateAsc(
                SUBJECT_ID, LocalDate.of(1000, 1, 1), LocalDate.of(1000, 1, 31)))
                .thenReturn(List.of());
        when(dailyRecordService.findBySubjectIdAndRecordDateBetweenOrderByRecordDateAsc(
                SUBJECT_ID, LocalDate.of(9999, 12, 1), LocalDate.of(9999, 12, 31)))
                .thenReturn(List.of());

        // MySQL DATE 지원 범위 양끝(1000-01, 9999-12)은 정상 조회다.
        assertThat(dailyTimelineService.getMonthlyDailyRecords("v1", SUBJECT_ID, 1000, 1).dailyRecords())
                .isEmpty();
        assertThat(dailyTimelineService.getMonthlyDailyRecords("v1", SUBJECT_ID, 9999, 12).dailyRecords())
                .isEmpty();
    }

    @Test
    void getMonthlyDailyRecords_rejectsOutOfRangeYearOrMonthBeforeQuerying() {
        // 범위 밖 입력은 catch-all 500이 아니라 IllegalArgumentException(400 -400)으로 수렴한다.
        for (int[] invalid : new int[][] {{999, 5}, {10000, 5}, {2026, 0}, {2026, 13}}) {
            assertThatThrownBy(() ->
                    dailyTimelineService.getMonthlyDailyRecords("v1", SUBJECT_ID, invalid[0], invalid[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(dailyRecordService, timelineEventService, timelineEventItemService,
                timelineItemService);
    }

    @Test
    void getDailyTimeline_pollingRecordMissing_throwsDraftResultNotFound() {
        when(dailyRecordService.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dailyTimelineService.getDailyTimeline(999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.DRAFT_RESULT_NOT_FOUND));
    }

    @Test
    void getDailyTimeline_ownedRecordMissing_throwsDailyRecordNotFound() {
        when(dailyRecordService.findByDailyRecordIdAndSubjectId(999L, SUBJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dailyTimelineService.getDailyTimeline("v1", SUBJECT_ID, 999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND));
    }
}
