package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoPayloadRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Event 편집 외부 오케스트레이션과 memo PUT 경로의 단위 검증. */
@ExtendWith(MockitoExtension.class)
class TimelineEventEditServiceTest {

    private static final String VERSION = "v1";
    private static final java.util.UUID SUBJECT_ID =
            com.laimory.server.testsupport.TestSubjects.id(7L);
    private static final java.util.UUID OTHER_SUBJECT_ID =
            com.laimory.server.testsupport.TestSubjects.id(999L);
    private static final Long EVENT_ID = 11L;
    private static final Long RECORD_ID = 100L;
    private static final int MAX_PHOTO_COUNT = 2;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 7, 8);
    private static final LocalDateTime ORIGINAL_START = LocalDateTime.of(2026, 7, 8, 9, 0);
    private static final LocalDateTime ORIGINAL_END = LocalDateTime.of(2026, 7, 8, 10, 0);
    private static final LocalDateTime NEW_START = LocalDateTime.of(2026, 7, 8, 14, 0);
    private static final LocalDateTime NEW_END = LocalDateTime.of(2026, 7, 8, 15, 30);
    private static final String RAW_ID_1 = "0190a1b2-0001-7000-8000-000000000001";
    private static final String RAW_ID_2 = "0190a1b2-0002-7000-8000-000000000002";
    private static final String FILENAME_1 = "0190a1b2-0001-7000-8000-000000000001.jpg";
    private static final String FILENAME_2 = "0190a1b2-0002-7000-8000-000000000002.png";
    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineEventEditTransactionService transactionService;

    private TimelineEventEditService service;

    @BeforeEach
    void setUp() {
        service = new TimelineEventEditService(
                timelineEventService,
                dailyRecordService,
                transactionService,
                MAX_PHOTO_COUNT);
    }

    // --- PATCH owner/DRAFT 선검증 ---

    @Test
    void updateEvent_unknownEventPrecedesRequestValidation() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(dailyRecordService, transactionService);
    }

    @Test
    void updateEvent_missingOrForeignRecordIsHiddenBeforeRequestValidation() {
        TimelineEvent event = originalEvent();
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(OTHER_SUBJECT_ID)));

        assertThatThrownBy(() -> service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(transactionService);
    }

    @Test
    void updateEvent_savedRecordPrecedesRequestValidation() {
        TimelineEvent event = originalEvent();
        DailyRecord saved = draftRecordOf(SUBJECT_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1003);
                });

        verifyNoInteractions(transactionService);
    }

    // --- PATCH scalar/memo 정규화와 검증 ---

    @Test
    void updateEvent_omittedOrEmptyPhotosNormalizesScalarsAndCallsWriter() {
        stubOwnedDraftEvent();
        UpdateTimelineEventRequest request = request(
                TimelineEventType.MEAL, "  a  ", "   ", NEW_START, NEW_START,
                null, false, List.of());

        service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, request);

        ArgumentCaptor<TimelineEventEditCommand> commandCaptor =
                ArgumentCaptor.forClass(TimelineEventEditCommand.class);
        verify(transactionService).updateEvent(eq(SUBJECT_ID), eq(EVENT_ID), commandCaptor.capture());
        TimelineEventEditCommand command = commandCaptor.getValue();
        assertThat(command.eventType()).isEqualTo(TimelineEventType.MEAL);
        assertThat(command.title()).isEqualTo("a");
        assertThat(command.subtitle()).isNull();
        assertThat(command.startAt()).isEqualTo(NEW_START);
        assertThat(command.endAt()).isEqualTo(NEW_START);
        assertThat(command.memoPresent()).isFalse();
        assertThat(command.memo()).isNull();
        assertThat(command.photosToAdd()).isEmpty();
    }

    @Test
    void updateEvent_presentMemoPreservesNonBlankRawTextInCommand() {
        stubOwnedDraftEvent();
        UpdateTimelineEventRequest request = request(
                null, "제목", null, NEW_START, null, " 앞뒤 공백 메모 ", true, List.of());

        service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, request);

        ArgumentCaptor<TimelineEventEditCommand> commandCaptor =
                ArgumentCaptor.forClass(TimelineEventEditCommand.class);
        verify(transactionService).updateEvent(eq(SUBJECT_ID), eq(EVENT_ID), commandCaptor.capture());
        assertThat(commandCaptor.getValue().memoPresent()).isTrue();
        assertThat(commandCaptor.getValue().memo()).isEqualTo(" 앞뒤 공백 메모 ");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void updateEvent_presentNullOrBlankMemoNormalizesToRemoval(String memo) {
        stubOwnedDraftEvent();
        UpdateTimelineEventRequest request = request(
                null, "제목", null, NEW_START, null, memo, true, List.of());

        service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, request);

        ArgumentCaptor<TimelineEventEditCommand> commandCaptor =
                ArgumentCaptor.forClass(TimelineEventEditCommand.class);
        verify(transactionService).updateEvent(eq(SUBJECT_ID), eq(EVENT_ID), commandCaptor.capture());
        assertThat(commandCaptor.getValue().memoPresent()).isTrue();
        assertThat(commandCaptor.getValue().memo()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidScalarRequests")
    void updateEvent_rejectsInvalidScalarInputBeforeWriter(String ignored,
                                                           UpdateTimelineEventRequest request) {
        stubOwnedDraftEvent();

        assertThatThrownBy(() -> service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, request))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(transactionService);
    }

    @Test
    void updateEvent_rejectsOversizedMemoBeforeWriter() {
        stubOwnedDraftEvent();
        UpdateTimelineEventRequest request = request(
                null, "제목", null, NEW_START, null, "m".repeat(501), true,
                List.of(photo(RAW_ID_1, FILENAME_1, "content://first")));

        assertThatThrownBy(() -> service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, request))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(transactionService);
    }

    // --- PATCH PHOTO 검증/dedupe ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPhotoLists")
    void updateEvent_rejectsInvalidPhotoBeforeWriter(String ignored,
                                                     List<UpdateTimelineEventPhotoRequest> photos) {
        stubOwnedDraftEvent();
        UpdateTimelineEventRequest request = request(
                null, "제목", null, NEW_START, null, null, false, photos);

        assertThatThrownBy(() -> service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, request))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(transactionService);
    }

    @Test
    void updateEvent_checksPhotoCountBeforeRawIdDedupe() {
        stubOwnedDraftEvent();
        UpdateTimelineEventPhotoRequest samePhoto = photo(RAW_ID_1, FILENAME_1, "content://same");
        UpdateTimelineEventRequest request = request(
                null, "제목", null, NEW_START, null, null, false,
                List.of(samePhoto, samePhoto, samePhoto));

        assertThatThrownBy(() -> service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, request))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.PHOTO_COUNT_EXCEEDED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1004);
                    assertThat(exception.getArgs()).containsExactly(MAX_PHOTO_COUNT);
                });

        verifyNoInteractions(transactionService);
    }

    @Test
    void updateEvent_invalidRawIdMessageDoesNotContainRawId() {
        // GlobalExceptionHandler가 IAE 메시지를 로그에 남기므로 rawId 원문을 메시지에 싣지 않는다.
        stubOwnedDraftEvent();
        String invalidRawId = "0190A1B2-0001-7000-8000-000000000001";
        UpdateTimelineEventRequest request = request(
                null, "제목", null, NEW_START, null, null, false,
                List.of(photo(invalidRawId, FILENAME_1, "content://photo")));

        assertThatThrownBy(() -> service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, request))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(invalidRawId));

        verifyNoInteractions(transactionService);
    }

    @Test
    void updateEvent_userTitleAndMemoWithPiiLikeTextAreStoredVerbatim() {
        // 사용자 입력 원문 보존 계약 — Event PATCH title/memo는 redaction 없이 DB 원문 저장한다
        // (User Memory AI 전달 시점에만 치환). PII 형태 텍스트도 command에 그대로 실려야 한다.
        stubOwnedDraftEvent();
        String titleWithPii = "친구 010-1234-5678에게 전화한 날";
        String memoWithPii = "메일 yun@example.com로 보냈다";
        UpdateTimelineEventRequest request = request(
                null, titleWithPii, null, NEW_START, null, memoWithPii, true, List.of());

        service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, request);

        ArgumentCaptor<TimelineEventEditCommand> commandCaptor =
                ArgumentCaptor.forClass(TimelineEventEditCommand.class);
        verify(transactionService).updateEvent(eq(SUBJECT_ID), eq(EVENT_ID), commandCaptor.capture());
        assertThat(commandCaptor.getValue().title()).isEqualTo(titleWithPii);
        assertThat(commandCaptor.getValue().memo()).isEqualTo(memoWithPii);
    }

    @Test
    void updateMemo_piiLikeTextIsStoredVerbatim() {
        // memo PUT도 원문 저장 계약이다 — 조회 응답에서 placeholder로 바뀌지 않는다.
        TimelineEvent event = stubOwnedDraftEvent();
        String memoWithPii = "연락처 010-1234-5678 저장";

        service.updateMemo(VERSION, SUBJECT_ID, EVENT_ID, memoWithPii);

        assertThat(event.getMemo()).isEqualTo(memoWithPii);
    }

    @Test
    void updateEvent_duplicateRawIdKeepsFirstPhoto() {
        stubOwnedDraftEvent();
        UpdateTimelineEventPhotoRequest first = new UpdateTimelineEventPhotoRequest(
                RAW_ID_1, NEW_START, null,
                new UpdateTimelineEventPhotoPayloadRequest(FILENAME_1, "content://first", 37.1, 127.1));
        UpdateTimelineEventPhotoRequest duplicate = new UpdateTimelineEventPhotoRequest(
                RAW_ID_1, NEW_END, NEW_END,
                new UpdateTimelineEventPhotoPayloadRequest(FILENAME_2, "content://second", 38.2, 128.2));
        UpdateTimelineEventRequest request = request(
                null, "제목", null, NEW_START, null, null, false, List.of(first, duplicate));

        service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, request);

        ArgumentCaptor<TimelineEventEditCommand> commandCaptor =
                ArgumentCaptor.forClass(TimelineEventEditCommand.class);
        verify(transactionService).updateEvent(eq(SUBJECT_ID), eq(EVENT_ID), commandCaptor.capture());
        assertThat(commandCaptor.getValue().photosToAdd()).containsExactly(
                new TimelineEventEditCommand.PhotoToAdd(
                        RAW_ID_1, NEW_START, null, FILENAME_1, "content://first", 37.1, 127.1));
    }

    // --- PATCH PHOTO writer delegation ---

    @Test
    void updateEvent_nonEmptyPhotosCallsWriterDirectly() {
        stubOwnedDraftEvent();
        UpdateTimelineEventRequest request = requestWithOnePhoto();

        service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, request);

        verify(transactionService).updateEvent(eq(SUBJECT_ID), eq(EVENT_ID), any());
    }

    @Test
    void updateEvent_writerFailurePropagates() {
        stubOwnedDraftEvent();
        IllegalStateException failure = new IllegalStateException("writer failed");
        doThrow(failure).when(transactionService).updateEvent(eq(SUBJECT_ID), eq(EVENT_ID), any());

        assertThatThrownBy(() -> service.updateEvent(VERSION, SUBJECT_ID, EVENT_ID, requestWithOnePhoto()))
                .isSameAs(failure);
    }

    // --- memo PUT ---

    @Test
    void updateMemo_storesRawTextAndDoesNotUsePatchWriter() {
        TimelineEvent event = stubOwnedDraftEvent();

        service.updateMemo(VERSION, SUBJECT_ID, EVENT_ID, " 앞뒤 공백 메모 ");

        assertThat(event.getMemo()).isEqualTo(" 앞뒤 공백 메모 ");
        assertThat(event.getTitle()).isEqualTo("원래 제목");
        assertThat(event.getStartAt()).isEqualTo(ORIGINAL_START);
        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void updateMemo_nullOrBlankRemovesMemo(String memo) {
        TimelineEvent event = stubOwnedDraftEvent();
        ReflectionTestUtils.setField(event, "memo", "기존 메모");

        service.updateMemo(VERSION, SUBJECT_ID, EVENT_ID, memo);

        assertThat(event.getMemo()).isNull();
    }

    @Test
    void updateMemo_blankCheckPrecedesLengthAndExactly500NonBlankCharsAreAccepted() {
        TimelineEvent event = stubOwnedDraftEvent();
        ReflectionTestUtils.setField(event, "memo", "기존 메모");

        service.updateMemo(VERSION, SUBJECT_ID, EVENT_ID, " ".repeat(501));
        assertThat(event.getMemo()).isNull();

        String maximumMemo = "가".repeat(500);
        service.updateMemo(VERSION, SUBJECT_ID, EVENT_ID, maximumMemo);
        assertThat(event.getMemo()).isEqualTo(maximumMemo);
    }

    @Test
    void updateMemo_rejects501CharsWithoutMutation() {
        TimelineEvent event = stubOwnedDraftEvent();
        ReflectionTestUtils.setField(event, "memo", "기존 메모");

        assertThatThrownBy(() -> service.updateMemo(VERSION, SUBJECT_ID, EVENT_ID, "a".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(event.getMemo()).isEqualTo("기존 메모");
        verifyNoInteractions(transactionService);
    }

    @Test
    void updateMemo_savedRecordRejectionPrecedesMemoValidation() {
        TimelineEvent event = originalEvent();
        DailyRecord saved = draftRecordOf(SUBJECT_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.updateMemo(VERSION, SUBJECT_ID, EVENT_ID, "a".repeat(501)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(-1003));

        verifyNoInteractions(transactionService);
    }

    @Test
    void updateMemo_hidesUnknownEventAs404() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateMemo(VERSION, SUBJECT_ID, EVENT_ID, "메모"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });

        verifyNoInteractions(transactionService);
    }

    private TimelineEvent stubOwnedDraftEvent() {
        TimelineEvent event = originalEvent();
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(SUBJECT_ID)));
        return event;
    }

    private TimelineEvent originalEvent() {
        TimelineEvent event = TimelineEvent.of(
                RECORD_ID, TimelineEventType.REST, ORIGINAL_START, ORIGINAL_END, "원래 제목", "원래 부제목", null);
        ReflectionTestUtils.setField(event, "timelineEventId", EVENT_ID);
        return event;
    }

    private DailyRecord draftRecordOf(java.util.UUID subjectId) {
        DailyRecord record = DailyRecord.createDraft(
                subjectId, RECORD_DATE, LocalDateTime.of(2026, 7, 8, 12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }

    private static UpdateTimelineEventRequest requestWithOnePhoto() {
        return request(
                null, "제목", null, NEW_START, null, null, false,
                List.of(photo(RAW_ID_1, FILENAME_1, "content://first")));
    }

    private static UpdateTimelineEventPhotoRequest photo(String rawId, String filename, String clientPhotoUri) {
        return new UpdateTimelineEventPhotoRequest(
                rawId,
                NEW_START,
                null,
                new UpdateTimelineEventPhotoPayloadRequest(filename, clientPhotoUri, 37.5665, 126.978));
    }

    private static UpdateTimelineEventRequest request(
            TimelineEventType eventType,
            String title,
            String subtitle,
            LocalDateTime startAt,
            LocalDateTime endAt,
            String memo,
            boolean memoPresent,
            List<UpdateTimelineEventPhotoRequest> photos) {
        return new UpdateTimelineEventRequest(
                title, subtitle, startAt, endAt, eventType, memo, memoPresent, photos);
    }

    private static Stream<Arguments> invalidScalarRequests() {
        return Stream.of(
                Arguments.of("null title", request(null, null, null, NEW_START, null, null, false, List.of())),
                Arguments.of("blank title", request(null, "   ", null, NEW_START, null, null, false, List.of())),
                Arguments.of("title over 255", request(
                        null, "a".repeat(256), null, NEW_START, null, null, false, List.of())),
                Arguments.of("subtitle over 255", request(
                        null, "제목", "b".repeat(256), NEW_START, null, null, false, List.of())),
                Arguments.of("null startAt", request(null, "제목", null, null, null, null, false, List.of())),
                Arguments.of("endAt before startAt", request(
                        null, "제목", null, NEW_START, NEW_START.minusNanos(1), null, false, List.of())));
    }

    private static Stream<Arguments> invalidPhotoLists() {
        return Stream.of(
                Arguments.of("null photosToAdd", null),
                Arguments.of("null element", java.util.Arrays.asList((UpdateTimelineEventPhotoRequest) null)),
                Arguments.of("blank rawId", List.of(photo("   ", FILENAME_1, "content://photo"))),
                // canonical lowercase UUID(version 무관)가 아니면 전부 400 — draft source와 같은 규칙.
                Arguments.of("rawId over 36", List.of(photo("r".repeat(37), FILENAME_1, "content://photo"))),
                Arguments.of("uppercase uuid rawId", List.of(
                        photo("0190A1B2-0001-7000-8000-000000000001", FILENAME_1, "content://photo"))),
                Arguments.of("ulid-like rawId", List.of(
                        photo("01ARZ3NDEKTSV4RRFFQ69G5FAV", FILENAME_1, "content://photo"))),
                Arguments.of("non-uuid 36 chars rawId", List.of(
                        photo("x".repeat(36), FILENAME_1, "content://photo"))),
                Arguments.of("null payload", List.of(
                        new UpdateTimelineEventPhotoRequest(RAW_ID_1, NEW_START, null, null))),
                Arguments.of("invalid filename", List.of(photo(RAW_ID_1, "../photo.jpg", "content://photo"))),
                Arguments.of("blank clientPhotoUri", List.of(photo(RAW_ID_1, FILENAME_1, "   "))));
    }
}
