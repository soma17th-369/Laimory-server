package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Event 편집 오케스트레이터 단위 검증: 조회→소유권/상태 검증→입력 검증→변경(대입)→응답 조립 순서와
 * 소유권 은닉(404)·SAVED 거절(409)·검증 규칙(IAE→400)·Item 불변을 고정한다. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class TimelineEventEditServiceTest {

    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineEventItemService timelineEventItemService;
    @Mock
    private TimelineItemService timelineItemService;

    @InjectMocks
    private TimelineEventEditService service;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VERSION = "v1";
    private static final long USER_ID = 7L;
    private static final Long EVENT_ID = 11L;
    private static final Long RECORD_ID = 100L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 7, 8);
    private static final LocalDateTime ORIGINAL_START = LocalDateTime.of(2026, 7, 8, 9, 0);
    private static final LocalDateTime ORIGINAL_END = LocalDateTime.of(2026, 7, 8, 10, 0);
    private static final LocalDateTime NEW_START = LocalDateTime.of(2026, 7, 8, 14, 0);
    private static final LocalDateTime NEW_END = LocalDateTime.of(2026, 7, 8, 15, 30);

    private static final TimelineEventType ORIGINAL_TYPE = TimelineEventType.REST;

    private TimelineEvent originalEvent() {
        TimelineEvent event = TimelineEvent.of(RECORD_ID, ORIGINAL_TYPE, ORIGINAL_START, ORIGINAL_END,
                "원래 제목", "원래 부제목");
        ReflectionTestUtils.setField(event, "timelineEventId", EVENT_ID);
        return event;
    }

    private DailyRecord draftRecordOf(long userId) {
        DailyRecord record = DailyRecord.createDraft(userId, RECORD_DATE,
                LocalDateTime.of(2026, 7, 8, 12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }

    /** 소유한 DRAFT record 위의 이벤트 조회 스텁(공통 경로). */
    private TimelineEvent stubOwnedDraftEvent() {
        TimelineEvent event = originalEvent();
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        return event;
    }

    private TimelineItem photoItem() {
        TimelineItem item = TimelineItem.of(ItemType.PHOTO, "raw-21", ORIGINAL_START, null,
                MAPPER.valueToTree(new PhotoPayload("u.jpg", "content://x", 1.0, 2.0, null,
                        "https://cdn.example/u.jpg")));
        ReflectionTestUtils.setField(item, "timelineItemId", 21L);
        return item;
    }

    // --- updateEvent: DRAFT 수정 성공 ---

    @Test
    void updateEvent_replacesAllFourFieldsAndKeepsMemoAndItems() {
        TimelineEvent event = stubOwnedDraftEvent();
        ReflectionTestUtils.setField(event, "memo", "지켜야 할 메모");
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID))
                .thenReturn(List.of(TimelineEventItem.of(EVENT_ID, 21L)));
        when(timelineItemService.findByIds(List.of(21L))).thenReturn(List.of(photoItem()));

        TimelineEventResponse response =
                service.updateEvent(VERSION, USER_ID, EVENT_ID, TimelineEventType.MEAL, "새 제목", "새 부제목", NEW_START, NEW_END);

        // 4개 필드 전체 교체(절대값 대입) + 시간은 입력 그대로(+10분 보정 없음). eventType은 명시 시 교체.
        assertThat(event.getEventType()).isEqualTo(TimelineEventType.MEAL);
        assertThat(event.getTitle()).isEqualTo("새 제목");
        assertThat(event.getSubtitle()).isEqualTo("새 부제목");
        assertThat(event.getStartAt()).isEqualTo(NEW_START);
        assertThat(event.getEndAt()).isEqualTo(NEW_END);
        // memo는 이 API로 바뀌지 않는다.
        assertThat(event.getMemo()).isEqualTo("지켜야 할 메모");

        assertThat(response.timelineEventId()).isEqualTo(EVENT_ID);
        assertThat(response.eventType()).isEqualTo(TimelineEventType.MEAL);
        assertThat(response.title()).isEqualTo("새 제목");
        assertThat(response.subtitle()).isEqualTo("새 부제목");
        assertThat(response.startAt()).isEqualTo(NEW_START);
        assertThat(response.endAt()).isEqualTo(NEW_END);
        assertThat(response.memo()).isEqualTo("지켜야 할 메모");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).timelineItemId()).isEqualTo(21L);

        // Item 불변: item 서비스 호출은 응답 조립용 조회뿐이고, 영속은 dirty checking이라 save 호출도 없다.
        verify(timelineEventItemService).findByTimelineEventId(EVENT_ID);
        verify(timelineItemService, never()).save(any());
        verify(timelineEventService, never()).save(any());
    }

    @Test
    void updateEvent_allowsEndAtEqualToStartAt_andStoresTimesVerbatim() {
        TimelineEvent event = stubOwnedDraftEvent();
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID)).thenReturn(List.of());

        service.updateEvent(VERSION, USER_ID, EVENT_ID, null, "제목", null, NEW_START, NEW_START);

        // endAt == startAt은 허용(0분 구간)이고 nudge·clamp 같은 보정 없이 그대로 저장된다.
        assertThat(event.getStartAt()).isEqualTo(NEW_START);
        assertThat(event.getEndAt()).isEqualTo(NEW_START);
    }

    @Test
    void updateEvent_nullSubtitleAndEndAtClearBothFields() {
        TimelineEvent event = stubOwnedDraftEvent();
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID)).thenReturn(List.of());

        service.updateEvent(VERSION, USER_ID, EVENT_ID, null, "제목", null, NEW_START, null);

        // subtitle/endAt의 null은 "비움"이다(4필드 절대값 대입 계약).
        assertThat(event.getSubtitle()).isNull();
        assertThat(event.getEndAt()).isNull();
    }

    @Test
    void updateEvent_trimsTitleAndSubtitle_singleCharTitleAllowed() {
        TimelineEvent event = stubOwnedDraftEvent();
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID)).thenReturn(List.of());

        service.updateEvent(VERSION, USER_ID, EVENT_ID, null, "  a  ", "  b  ", NEW_START, null);

        // 앞뒤 공백 제거 후 저장 — trim 후 1자 title도 유효 하한.
        assertThat(event.getTitle()).isEqualTo("a");
        assertThat(event.getSubtitle()).isEqualTo("b");
    }

    @Test
    void updateEvent_acceptsTitleAndSubtitleAtMaxLength255AfterTrim() {
        TimelineEvent event = stubOwnedDraftEvent();
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID)).thenReturn(List.of());
        String title255 = "a".repeat(255);
        String subtitle255 = "b".repeat(255);

        // 길이는 trim 후 기준으로 센다 — 앞뒤 공백을 붙여도 trim 후 255자면 통과.
        service.updateEvent(VERSION, USER_ID, EVENT_ID, null, "  " + title255 + "  ", subtitle255, NEW_START, null);

        assertThat(event.getTitle()).isEqualTo(title255);
        assertThat(event.getSubtitle()).isEqualTo(subtitle255);
    }

    // --- updateEvent: 입력 검증(IAE → 400) ---

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void updateEvent_rejectsMissingOrBlankTitle(String title) {
        TimelineEvent event = stubOwnedDraftEvent();

        assertThatThrownBy(() -> service.updateEvent(VERSION, USER_ID, EVENT_ID, null, title, "부제목", NEW_START, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(event.getTitle()).isEqualTo("원래 제목");
    }

    @Test
    void updateEvent_rejectsTitleOver255Chars() {
        TimelineEvent event = stubOwnedDraftEvent();

        assertThatThrownBy(() -> service.updateEvent(
                VERSION, USER_ID, EVENT_ID, null, "a".repeat(256), null, NEW_START, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(event.getTitle()).isEqualTo("원래 제목");
    }

    @Test
    void updateEvent_subtitleBlankBecomesNull() {
        TimelineEvent event = stubOwnedDraftEvent();
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID)).thenReturn(List.of());

        service.updateEvent(VERSION, USER_ID, EVENT_ID, null, "제목", "   ", NEW_START, null);

        assertThat(event.getSubtitle()).isNull();
    }

    @Test
    void updateEvent_rejectsSubtitleOver255Chars() {
        TimelineEvent event = stubOwnedDraftEvent();

        assertThatThrownBy(() -> service.updateEvent(
                VERSION, USER_ID, EVENT_ID, null, "제목", "b".repeat(256), NEW_START, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(event.getSubtitle()).isEqualTo("원래 부제목");
    }

    @Test
    void updateEvent_rejectsMissingStartAt() {
        TimelineEvent event = stubOwnedDraftEvent();

        assertThatThrownBy(() -> service.updateEvent(VERSION, USER_ID, EVENT_ID, null, "제목", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(event.getStartAt()).isEqualTo(ORIGINAL_START);
    }

    @Test
    void updateEvent_rejectsEndAtBeforeStartAt() {
        TimelineEvent event = stubOwnedDraftEvent();

        assertThatThrownBy(() -> service.updateEvent(
                VERSION, USER_ID, EVENT_ID, null, "제목", null, NEW_START, NEW_START.minusMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        // 검증 실패면 아무 필드도 안 바뀐다(대입 전 검증).
        assertThat(event.getStartAt()).isEqualTo(ORIGINAL_START);
        assertThat(event.getEndAt()).isEqualTo(ORIGINAL_END);
        assertThat(event.getTitle()).isEqualTo("원래 제목");
    }

    // --- updateEvent: 소유권 은닉(404)·상태 거절(409) ---

    @Test
    void updateEvent_hidesUnknownEventAs404() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateEvent(VERSION, USER_ID, EVENT_ID, null, "제목", null, NEW_START, null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_0404);
                });
        verify(timelineEventItemService, never()).findByTimelineEventId(anyLong());
    }

    @Test
    void updateEvent_hidesMissingRecordAs404() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(originalEvent()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateEvent(VERSION, USER_ID, EVENT_ID, null, "제목", null, NEW_START, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
    }

    @Test
    void updateEvent_hidesForeignRecordAs404() {
        // 타인 record 위의 이벤트 — 존재 여부를 유출하지 않도록 "없음"과 같은 404로 은닉한다.
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(originalEvent()));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(999L)));

        assertThatThrownBy(() -> service.updateEvent(VERSION, USER_ID, EVENT_ID, null, "제목", null, NEW_START, null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_0404);
                });
    }

    @Test
    void updateEvent_rejectsSavedRecordWith1003() {
        TimelineEvent event = originalEvent();
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        DailyRecord saved = draftRecordOf(USER_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.updateEvent(VERSION, USER_ID, EVENT_ID, null, "새 제목", null, NEW_START, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1003));
        assertThat(event.getTitle()).isEqualTo("원래 제목");
        assertThat(event.getStartAt()).isEqualTo(ORIGINAL_START);
    }

    @Test
    void updateEvent_savedRejectionPrecedesInputValidation() {
        // SAVED는 "모든 작업 전에" 거절이다 — 입력이 불량(title null)이어도 400이 아니라 409가 나간다.
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(originalEvent()));
        DailyRecord saved = draftRecordOf(USER_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.updateEvent(VERSION, USER_ID, EVENT_ID, null, null, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1003));
    }

    // --- updateEvent: eventType(optional) ---

    @Test
    void updateEvent_keepsCurrentEventTypeWhenOmitted() {
        TimelineEvent event = stubOwnedDraftEvent();
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID)).thenReturn(List.of());

        // eventType null = 요청에 키 누락(명시적 null은 역직렬화에서 400) — 현재 값을 유지한다.
        TimelineEventResponse response =
                service.updateEvent(VERSION, USER_ID, EVENT_ID, null, "새 제목", null, NEW_START, null);

        assertThat(event.getEventType()).isEqualTo(ORIGINAL_TYPE);
        assertThat(response.eventType()).isEqualTo(ORIGINAL_TYPE);
    }

    @Test
    void updateEvent_validationFailureKeepsEventType() {
        TimelineEvent event = stubOwnedDraftEvent();

        // 대입 전 검증 실패 시 eventType도 함께 보존된다.
        assertThatThrownBy(() -> service.updateEvent(
                VERSION, USER_ID, EVENT_ID, TimelineEventType.MEAL, null, null, NEW_START, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(event.getEventType()).isEqualTo(ORIGINAL_TYPE);
    }

    // --- updateMemo ---

    @Test
    void updateMemo_storesRawTextWithoutTrim() {
        TimelineEvent event = stubOwnedDraftEvent();
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID))
                .thenReturn(List.of(TimelineEventItem.of(EVENT_ID, 21L)));
        when(timelineItemService.findByIds(List.of(21L))).thenReturn(List.of(photoItem()));

        TimelineEventResponse response = service.updateMemo(VERSION, USER_ID, EVENT_ID, " 앞뒤 공백 메모 ");

        // 원문 보존 — trim 없이 그대로 저장한다. 다른 필드(eventType 포함)는 불변.
        assertThat(event.getMemo()).isEqualTo(" 앞뒤 공백 메모 ");
        assertThat(event.getEventType()).isEqualTo(ORIGINAL_TYPE);
        assertThat(event.getTitle()).isEqualTo("원래 제목");
        assertThat(event.getStartAt()).isEqualTo(ORIGINAL_START);
        assertThat(response.memo()).isEqualTo(" 앞뒤 공백 메모 ");
        assertThat(response.items()).hasSize(1);

        verify(timelineEventItemService).findByTimelineEventId(EVENT_ID);
        verify(timelineItemService, never()).save(any());
        verify(timelineEventService, never()).save(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void updateMemo_removesMemoWhenNullOrBlank(String memo) {
        // null은 body {"memo": null}과 {}(필드 부재) 둘 다를 대표한다 — 컨트롤러가 absent를 null로 전달.
        TimelineEvent event = stubOwnedDraftEvent();
        ReflectionTestUtils.setField(event, "memo", "기존 메모");
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID)).thenReturn(List.of());

        TimelineEventResponse response = service.updateMemo(VERSION, USER_ID, EVENT_ID, memo);

        assertThat(event.getMemo()).isNull();
        assertThat(response.memo()).isNull();
    }

    @Test
    void updateMemo_blankCheckPrecedesLengthLimit() {
        // 공백뿐이면 길이와 무관하게 "제거"다 — 10,001자 공백도 400이 아니라 제거로 처리된다.
        TimelineEvent event = stubOwnedDraftEvent();
        ReflectionTestUtils.setField(event, "memo", "기존 메모");
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID)).thenReturn(List.of());

        service.updateMemo(VERSION, USER_ID, EVENT_ID, " ".repeat(10_001));

        assertThat(event.getMemo()).isNull();
    }

    @Test
    void updateMemo_acceptsExactly10000Chars() {
        TimelineEvent event = stubOwnedDraftEvent();
        when(timelineEventItemService.findByTimelineEventId(EVENT_ID)).thenReturn(List.of());
        String memo = "가".repeat(10_000);

        service.updateMemo(VERSION, USER_ID, EVENT_ID, memo);

        assertThat(event.getMemo()).isEqualTo(memo);
        assertThat(event.getMemo()).hasSize(10_000);
    }

    @Test
    void updateMemo_rejects10001Chars() {
        TimelineEvent event = stubOwnedDraftEvent();
        ReflectionTestUtils.setField(event, "memo", "기존 메모");

        assertThatThrownBy(() -> service.updateMemo(VERSION, USER_ID, EVENT_ID, "a".repeat(10_001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(event.getMemo()).isEqualTo("기존 메모");
    }

    @Test
    void updateMemo_rejectsSavedRecordWith1003() {
        TimelineEvent event = originalEvent();
        ReflectionTestUtils.setField(event, "memo", "기존 메모");
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        DailyRecord saved = draftRecordOf(USER_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.updateMemo(VERSION, USER_ID, EVENT_ID, "새 메모"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1003));
        assertThat(event.getMemo()).isEqualTo("기존 메모");
    }

    @Test
    void updateMemo_hidesUnknownEventAs404() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMemo(VERSION, USER_ID, EVENT_ID, "메모"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_0404);
                });
    }
}
