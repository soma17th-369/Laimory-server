package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.TestSubjects.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoPayloadRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoRequest;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.photo.PhotoUrlService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 공유 사진 컴포넌트의 정적 검증({@code requireValidPhotos})과 저장({@code link}) 반환 계약 단위 검증.
 * DB-dependent 분류({@code resolve})의 전 시나리오는 {@link TimelineEventEditTransactionServiceTest}가
 * 실제 인스턴스 주입으로 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineEventPhotoAddServiceTest {

    private static final UUID SUBJECT_ID = id(7L);
    private static final Long EVENT_ID = 11L;
    private static final int MAX_PHOTO_COUNT = 2;
    private static final LocalDateTime START = LocalDateTime.of(2026, 7, 8, 14, 0);
    private static final String RAW_ID_1 = "0190a1b2-0001-7000-8000-000000000001";
    private static final String FILENAME_1 = "0190a1b2-0001-7000-8000-000000000001.jpg";
    private static final String FILENAME_2 = "0190a1b2-0002-7000-8000-000000000002.png";
    private static final String PHOTO_URL = "https://cdn.example/user/photos/" + FILENAME_1;

    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private TimelineEventItemService timelineEventItemService;
    @Mock
    private TimelineItemService timelineItemService;
    @Mock
    private TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;
    @Mock
    private PhotoUrlService photoUrlService;

    private TimelineEventPhotoAddService service;

    @BeforeEach
    void setUp() {
        service = new TimelineEventPhotoAddService(
                timelineEventService,
                timelineEventItemService,
                timelineItemService,
                timelinePhotoDeleteJobService,
                photoUrlService,
                new ObjectMapper(),
                MAX_PHOTO_COUNT);
    }

    // --- requireValidPhotos ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPhotoLists")
    void requireValidPhotos_rejectsInvalidInput(String ignored, List<UpdateTimelineEventPhotoRequest> photos) {
        assertThatThrownBy(() -> service.requireValidPhotos(photos))
                .isInstanceOf(IllegalArgumentException.class);
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
                Arguments.of("fractional startAt", List.of(new UpdateTimelineEventPhotoRequest(
                        RAW_ID_1, START.plusNanos(1), null,
                        new UpdateTimelineEventPhotoPayloadRequest(
                                FILENAME_1, "content://photo", 37.5665, 126.978)))),
                Arguments.of("fractional endAt", List.of(new UpdateTimelineEventPhotoRequest(
                        RAW_ID_1, START, START.plusNanos(1),
                        new UpdateTimelineEventPhotoPayloadRequest(
                                FILENAME_1, "content://photo", 37.5665, 126.978)))),
                Arguments.of("null payload", List.of(
                        new UpdateTimelineEventPhotoRequest(RAW_ID_1, START, null, null))),
                Arguments.of("invalid filename", List.of(photo(RAW_ID_1, "../photo.jpg", "content://photo"))),
                Arguments.of("blank clientPhotoUri", List.of(photo(RAW_ID_1, FILENAME_1, "   "))));
    }

    @Test
    void requireValidPhotos_checksPhotoCountBeforeRawIdDedupe() {
        UpdateTimelineEventPhotoRequest samePhoto = photo(RAW_ID_1, FILENAME_1, "content://same");

        assertThatThrownBy(() -> service.requireValidPhotos(List.of(samePhoto, samePhoto, samePhoto)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.PHOTO_COUNT_EXCEEDED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1004);
                    assertThat(exception.getArgs()).containsExactly(MAX_PHOTO_COUNT);
                });
    }

    @Test
    void requireValidPhotos_invalidRawIdMessageDoesNotContainRawId() {
        // GlobalExceptionHandler가 IAE 메시지를 로그에 남기므로 rawId 원문을 메시지에 싣지 않는다.
        String invalidRawId = "0190A1B2-0001-7000-8000-000000000001";

        assertThatThrownBy(() -> service.requireValidPhotos(
                List.of(photo(invalidRawId, FILENAME_1, "content://photo"))))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(invalidRawId));
    }

    @Test
    void requireValidPhotos_duplicateRawIdKeepsFirstPhoto() {
        UpdateTimelineEventPhotoRequest first = new UpdateTimelineEventPhotoRequest(
                RAW_ID_1, START, null,
                new UpdateTimelineEventPhotoPayloadRequest(FILENAME_1, "content://first", 37.1, 127.1));
        UpdateTimelineEventPhotoRequest duplicate = new UpdateTimelineEventPhotoRequest(
                RAW_ID_1, START.plusHours(1), START.plusHours(1),
                new UpdateTimelineEventPhotoPayloadRequest(FILENAME_2, "content://second", 38.2, 128.2));

        List<TimelineEventPhotoAddService.PhotoToAdd> validated =
                service.requireValidPhotos(List.of(first, duplicate));

        assertThat(validated).containsExactly(new TimelineEventPhotoAddService.PhotoToAdd(
                RAW_ID_1, START, null, FILENAME_1, "content://first", 37.1, 127.1));
    }

    // --- link ---

    @Test
    void link_returnsExistingAndNewLinkedItemIdsAndSavesJunctions() {
        when(photoUrlService.buildSubjectUrl(FILENAME_1, SUBJECT_ID)).thenReturn(PHOTO_URL);
        when(timelineItemService.save(any(TimelineItem.class))).thenAnswer(invocation -> {
            TimelineItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "timelineItemId", 21L);
            return item;
        });
        TimelineEventPhotoAddService.PhotoChanges changes = new TimelineEventPhotoAddService.PhotoChanges(
                List.of(31L),
                List.of(new TimelineEventPhotoAddService.PhotoToAdd(
                        RAW_ID_1, START, null, FILENAME_1, "content://first", 37.5665, 126.978)));

        List<Long> linked = service.link(SUBJECT_ID, EVENT_ID, changes);

        // 반환 ID는 기존 재사용·job 재연결·신규를 모두 포함한다 — 생성 응답 조립의 입력이다.
        assertThat(linked).containsExactly(31L, 21L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineEventItem>> linksCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineEventItemService).saveAll(linksCaptor.capture());
        assertThat(linksCaptor.getValue())
                .extracting(TimelineEventItem::getTimelineItemId)
                .containsExactly(31L, 21L);
        assertThat(linksCaptor.getValue())
                .allSatisfy(link -> assertThat(link.getTimelineEventId()).isEqualTo(EVENT_ID));
        ArgumentCaptor<TimelineItem> itemCaptor = ArgumentCaptor.forClass(TimelineItem.class);
        verify(timelineItemService).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getItemType()).isEqualTo(ItemType.PHOTO);
        assertThat(itemCaptor.getValue().getPayload().path("photoUrl").asText()).isEqualTo(PHOTO_URL);
        assertThat(itemCaptor.getValue().getPayload().has("description")).isFalse();
    }

    @Test
    void link_emptyChangesSavesNothingAndReturnsEmpty() {
        assertThat(service.link(SUBJECT_ID, EVENT_ID, TimelineEventPhotoAddService.PhotoChanges.empty()))
                .isEmpty();
        verify(timelineEventItemService, never()).saveAll(any());
        verify(timelineItemService, never()).save(any());
    }

    private static UpdateTimelineEventPhotoRequest photo(String rawId, String filename, String clientPhotoUri) {
        return new UpdateTimelineEventPhotoRequest(
                rawId, START, null,
                new UpdateTimelineEventPhotoPayloadRequest(filename, clientPhotoUri, 37.5665, 126.978));
    }
}
