package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.TestSubjects.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.CreateTimelineEventRequest;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoPayloadRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoRequest;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 수동 Event 생성 use case 단위 검증.
 *
 * <p>고정하는 계약: DRAFT/SAVED 모두 생성 허용, 없음·타인 record는 404 은닉(사진 검증보다 먼저),
 * PATCH와 같은 상세·사진 입력 규칙, question/place/address는 항상 null, 사진 없으면 resolve/link
 * 미호출·items 빈 목록, 있으면 생성 ID target으로 resolve→link 순 호출 후 응답 items를 조회 경로와
 * 같은 정렬로 조립, save 1회, User Memory 무의존.
 */
@ExtendWith(MockitoExtension.class)
class TimelineEventCreateServiceTest {

    private static final String VERSION = "v1";
    private static final UUID SUBJECT_ID = id(7L);
    private static final Long RECORD_ID = 100L;
    private static final Long EVENT_ID = 11L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 7, 8);
    private static final LocalDateTime START = LocalDateTime.of(2026, 7, 8, 14, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 7, 8, 15, 0);
    private static final String RAW_ID_1 = "0190a1b2-0001-7000-8000-000000000001";
    private static final String FILENAME_1 = "0190a1b2-0001-7000-8000-000000000001.jpg";

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private TimelineItemService timelineItemService;
    @Mock
    private TimelineEventPhotoAddService timelineEventPhotoAddService;

    @InjectMocks
    private TimelineEventCreateService service;

    @Test
    void DRAFT_record에_생성하면_정규화된_입력과_AI_필드_null로_저장하고_응답을_조립한다() {
        stubOwnedRecord(DailyRecordStatus.DRAFT);
        stubSaveAssignsId();
        stubNoPhotos();

        TimelineEventResponse response = service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.REST, "  카페에서 휴식  ", "   ", START, END, null));

        ArgumentCaptor<TimelineEvent> eventCaptor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timelineEventService).save(eventCaptor.capture());
        TimelineEvent saved = eventCaptor.getValue();
        assertThat(saved.getDailyRecordId()).isEqualTo(RECORD_ID);
        assertThat(saved.getEventType()).isEqualTo(TimelineEventType.REST);
        assertThat(saved.getTitle()).isEqualTo("카페에서 휴식");
        assertThat(saved.getSubtitle()).isNull();
        assertThat(saved.getStartAt()).isEqualTo(START);
        assertThat(saved.getEndAt()).isEqualTo(END);
        assertThat(saved.getQuestion()).isNull();
        assertThat(saved.getPlace()).isNull();
        assertThat(saved.getAddress()).isNull();
        assertThat(saved.getMemo()).isNull();

        assertThat(response.timelineEventId()).isEqualTo(EVENT_ID);
        assertThat(response.question()).isNull();
        assertThat(response.place()).isNull();
        assertThat(response.address()).isNull();
        assertThat(response.items()).isEmpty();
    }

    @Test
    void SAVED_record에도_생성이_허용된다() {
        stubOwnedRecord(DailyRecordStatus.SAVED);
        stubSaveAssignsId();
        stubNoPhotos();

        TimelineEventResponse response = service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.MEAL, "점심", "회사 근처", START, null, null));

        assertThat(response.timelineEventId()).isEqualTo(EVENT_ID);
        verify(timelineEventService).save(any());
    }

    @Test
    void memo는_trim_없이_원문을_보존한다() {
        stubOwnedRecord(DailyRecordStatus.DRAFT);
        stubSaveAssignsId();
        stubNoPhotos();
        String memoWithPii = " 메일 yun@example.com로 보냈다 ";

        service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.REST, "제목", null, START, null, memoWithPii));

        ArgumentCaptor<TimelineEvent> eventCaptor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timelineEventService).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getMemo()).isEqualTo(memoWithPii);
    }

    @Test
    void 공백뿐인_memo는_메모_없음으로_정규화한다() {
        stubOwnedRecord(DailyRecordStatus.DRAFT);
        stubSaveAssignsId();
        stubNoPhotos();

        service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.REST, "제목", null, START, null, "   "));

        ArgumentCaptor<TimelineEvent> eventCaptor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timelineEventService).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getMemo()).isNull();
    }

    @Test
    void 해당_날짜의_기록이_없으면_사진_검증과_저장_없이_404로_은닉한다() {
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.REST, "제목", null, START, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });
        verifyNoInteractions(timelineEventService, timelineEventPhotoAddService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void 잘못된_입력은_저장_없이_400으로_거절한다(String ignored, CreateTimelineEventRequest request) {
        stubOwnedRecord(DailyRecordStatus.DRAFT);

        assertThatThrownBy(() -> service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE, request))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(timelineEventService);
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("null request", null),
                Arguments.of("null eventType", request(null, "제목", null, START, null, null)),
                Arguments.of("null title", request(TimelineEventType.REST, null, null, START, null, null)),
                Arguments.of("blank title", request(TimelineEventType.REST, "   ", null, START, null, null)),
                Arguments.of("title over 255", request(
                        TimelineEventType.REST, "a".repeat(256), null, START, null, null)),
                Arguments.of("subtitle over 255", request(
                        TimelineEventType.REST, "제목", "b".repeat(256), START, null, null)),
                Arguments.of("null startAt", request(TimelineEventType.REST, "제목", null, null, null, null)),
                Arguments.of("endAt before startAt", request(
                        TimelineEventType.REST, "제목", null, START, START.minusNanos(1), null)),
                Arguments.of("memo over 500", request(
                        TimelineEventType.REST, "제목", null, START, null, "m".repeat(501))));
    }

    // --- photosToAdd (#361) ---

    @Test
    void 유효_사진은_저장된_Event_ID로_resolve와_link를_순서대로_호출하고_응답_items를_정렬해_조립한다() {
        DailyRecord record = stubOwnedRecord(DailyRecordStatus.DRAFT);
        stubSaveAssignsId();
        List<UpdateTimelineEventPhotoRequest> requestPhotos = List.of(photoRequest());
        List<TimelineEventPhotoAddService.PhotoToAdd> validated = List.of(validatedPhoto());
        TimelineEventPhotoAddService.PhotoChanges changes =
                new TimelineEventPhotoAddService.PhotoChanges(List.of(31L), validated);
        when(timelineEventPhotoAddService.requireValidPhotos(requestPhotos)).thenReturn(validated);
        when(timelineEventPhotoAddService.resolve(eq(record), eq(EVENT_ID), eq(validated))).thenReturn(changes);
        when(timelineEventPhotoAddService.link(SUBJECT_ID, EVENT_ID, changes)).thenReturn(List.of(31L, 21L));
        // 조회 경로와 같은 정렬(startAt null 먼저·ID 오름차순)을 검증하기 위해 역순·null startAt을 섞는다.
        when(timelineItemService.findByIds(List.of(31L, 21L)))
                .thenReturn(List.of(item(21L, START.plusMinutes(5)), item(31L, null)));

        TimelineEventResponse response = service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.REST, "제목", null, START, null, null, requestPhotos));

        InOrder inOrder = inOrder(timelineEventService, timelineEventPhotoAddService);
        inOrder.verify(timelineEventService).save(any());
        inOrder.verify(timelineEventPhotoAddService).resolve(eq(record), eq(EVENT_ID), eq(validated));
        inOrder.verify(timelineEventPhotoAddService).link(SUBJECT_ID, EVENT_ID, changes);
        assertThat(response.items())
                .extracting(TimelineItemResponse::timelineItemId)
                .containsExactly(31L, 21L);
    }

    @Test
    void 사진이_없으면_resolve와_link를_호출하지_않는다() {
        stubOwnedRecord(DailyRecordStatus.DRAFT);
        stubSaveAssignsId();
        stubNoPhotos();

        TimelineEventResponse response = service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.REST, "제목", null, START, null, null));

        assertThat(response.items()).isEmpty();
        verify(timelineEventPhotoAddService, never()).resolve(any(), any(), anyList());
        verify(timelineEventPhotoAddService, never()).link(any(), any(), any());
        verifyNoInteractions(timelineItemService);
    }

    @Test
    void 사진_입력_검증_실패는_Event_저장_전에_거절된다() {
        stubOwnedRecord(DailyRecordStatus.DRAFT);
        when(timelineEventPhotoAddService.requireValidPhotos(anyList()))
                .thenThrow(new IllegalArgumentException("photo requires rawId: index=0"));

        assertThatThrownBy(() -> service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.REST, "제목", null, START, null, null, List.of(photoRequest()))))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(timelineEventService);
    }

    @Test
    void 사진_수_초과는_Event_저장_전에_1004로_거절된다() {
        stubOwnedRecord(DailyRecordStatus.DRAFT);
        when(timelineEventPhotoAddService.requireValidPhotos(anyList()))
                .thenThrow(new BusinessException(ExceptionType.PHOTO_COUNT_EXCEEDED, 20));

        assertThatThrownBy(() -> service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.REST, "제목", null, START, null, null, List.of(photoRequest()))))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.PHOTO_COUNT_EXCEEDED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1004);
                });

        verifyNoInteractions(timelineEventService);
    }

    private DailyRecord stubOwnedRecord(DailyRecordStatus status) {
        DailyRecord record = DailyRecord.createDraft(
                SUBJECT_ID, RECORD_DATE, LocalDateTime.of(2026, 7, 8, 12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        ReflectionTestUtils.setField(record, "status", status);
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.of(record));
        return record;
    }

    private void stubSaveAssignsId() {
        when(timelineEventService.save(any())).thenAnswer(invocation -> {
            TimelineEvent event = invocation.getArgument(0);
            ReflectionTestUtils.setField(event, "timelineEventId", EVENT_ID);
            return event;
        });
    }

    /** 사진 없는 시나리오 공통 — 빈 입력은 그대로 빈 목록으로 통과한다. */
    private void stubNoPhotos() {
        lenient().when(timelineEventPhotoAddService.requireValidPhotos(anyList())).thenReturn(List.of());
    }

    private TimelineItem item(Long itemId, LocalDateTime startAt) {
        TimelineItem item = TimelineItem.of(
                ItemType.PHOTO, RAW_ID_1, startAt, null,
                new ObjectMapper().createObjectNode().put("filename", FILENAME_1));
        ReflectionTestUtils.setField(item, "timelineItemId", itemId);
        return item;
    }

    private static UpdateTimelineEventPhotoRequest photoRequest() {
        return new UpdateTimelineEventPhotoRequest(
                RAW_ID_1, START.plusMinutes(5), null,
                new UpdateTimelineEventPhotoPayloadRequest(FILENAME_1, "content://photo/1", 37.5665, 126.978));
    }

    private static TimelineEventPhotoAddService.PhotoToAdd validatedPhoto() {
        return new TimelineEventPhotoAddService.PhotoToAdd(
                RAW_ID_1, START.plusMinutes(5), null, FILENAME_1, "content://photo/1", 37.5665, 126.978);
    }

    private static CreateTimelineEventRequest request(
            TimelineEventType eventType, String title, String subtitle,
            LocalDateTime startAt, LocalDateTime endAt, String memo) {
        return request(eventType, title, subtitle, startAt, endAt, memo, List.of());
    }

    private static CreateTimelineEventRequest request(
            TimelineEventType eventType, String title, String subtitle,
            LocalDateTime startAt, LocalDateTime endAt, String memo,
            List<UpdateTimelineEventPhotoRequest> photosToAdd) {
        return new CreateTimelineEventRequest(eventType, title, subtitle, startAt, endAt, memo, photosToAdd);
    }
}
