package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.TestSubjects.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.CreateTimelineEventRequest;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 수동 Event 생성 use case 단위 검증.
 *
 * <p>고정하는 계약: DRAFT/SAVED 모두 생성 허용, 없음·타인 record는 404 은닉, PATCH와 같은 상세 필드
 * 규칙, question/place/address는 항상 null·items는 빈 목록, save 1회, Item·S3·User Memory 무의존.
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

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineEventService timelineEventService;

    @InjectMocks
    private TimelineEventCreateService service;

    @Test
    void DRAFT_record에_생성하면_정규화된_입력과_AI_필드_null로_저장하고_응답을_조립한다() {
        stubOwnedRecord(DailyRecordStatus.DRAFT);
        stubSaveAssignsId();

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

        TimelineEventResponse response = service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.MEAL, "점심", "회사 근처", START, null, null));

        assertThat(response.timelineEventId()).isEqualTo(EVENT_ID);
        verify(timelineEventService).save(any());
    }

    @Test
    void memo는_trim_없이_원문을_보존한다() {
        stubOwnedRecord(DailyRecordStatus.DRAFT);
        stubSaveAssignsId();
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

        service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.REST, "제목", null, START, null, "   "));

        ArgumentCaptor<TimelineEvent> eventCaptor = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timelineEventService).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getMemo()).isNull();
    }

    @Test
    void 해당_날짜의_기록이_없으면_저장_없이_404로_은닉한다() {
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEvent(VERSION, SUBJECT_ID, RECORD_DATE,
                request(TimelineEventType.REST, "제목", null, START, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });
        verifyNoInteractions(timelineEventService);
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

    private void stubOwnedRecord(DailyRecordStatus status) {
        DailyRecord record = DailyRecord.createDraft(
                SUBJECT_ID, RECORD_DATE, LocalDateTime.of(2026, 7, 8, 12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        ReflectionTestUtils.setField(record, "status", status);
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.of(record));
    }

    private void stubSaveAssignsId() {
        when(timelineEventService.save(any())).thenAnswer(invocation -> {
            TimelineEvent event = invocation.getArgument(0);
            ReflectionTestUtils.setField(event, "timelineEventId", EVENT_ID);
            return event;
        });
    }

    private static CreateTimelineEventRequest request(
            com.laimory.server.timeline.TimelineEventType eventType, String title, String subtitle,
            LocalDateTime startAt, LocalDateTime endAt, String memo) {
        return new CreateTimelineEventRequest(eventType, title, subtitle, startAt, endAt, memo);
    }
}
