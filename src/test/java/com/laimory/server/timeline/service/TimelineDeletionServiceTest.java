package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 삭제 오케스트레이터 단위 검증: 사전 검증(404 은닉·SAVED 409) → guard 선점(delete holder) →
 * junction 기반 exclusive Item 판정 → exclusive PHOTO key만 수집(파싱 실패/blank orphan skip·중복 제거)
 * → S3 배치 → DB 삭제 bean → finally 해제(성공·1017·500 모든 종료 경로) 순서를 고정한다.
 * 인프라 0(S3는 storage 서비스 mock).
 */
@ExtendWith(MockitoExtension.class)
class TimelineDeletionServiceTest {

    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private TimelineEventItemService timelineEventItemService;
    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineItemService timelineItemService;
    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private S3PhotoStorageService s3PhotoStorageService;
    @Mock
    private TimelineDeletionTransactionService timelineDeletionTransactionService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VERSION = "v1";
    private static final long USER_ID = 7L;
    private static final Long EVENT_ID = 11L;
    private static final Long RECORD_ID = 100L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 7, 8);

    private TimelineDeletionService service;

    @BeforeEach
    void setUp() {
        service = new TimelineDeletionService(timelineEventService, timelineEventItemService, dailyRecordService,
                timelineItemService, timelineTaskService, s3PhotoStorageService,
                timelineDeletionTransactionService, MAPPER);
    }

    private TimelineEvent event(Long eventId, Long recordId) {
        TimelineEvent event = TimelineEvent.of(recordId, TimelineEventType.UNKNOWN, RECORD_DATE.atTime(9, 0), null, "제목", null);
        ReflectionTestUtils.setField(event, "timelineEventId", eventId);
        return event;
    }

    private DailyRecord draftRecordOf(long userId) {
        DailyRecord record = DailyRecord.createDraft(userId, RECORD_DATE, RECORD_DATE.atTime(12, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }

    private TimelineItem photoItem(long itemId, String filename) {
        TimelineItem item = TimelineItem.of(ItemType.PHOTO, "raw-" + itemId, RECORD_DATE.atTime(9, 5), null,
                MAPPER.valueToTree(new PhotoPayload(filename, "content://x", 1.0, 2.0, null,
                        "https://cdn.example/" + filename)));
        ReflectionTestUtils.setField(item, "timelineItemId", itemId);
        return item;
    }

    private TimelineItem calendarItem(long itemId) {
        TimelineItem item = TimelineItem.of(ItemType.CALENDAR, "raw-" + itemId, RECORD_DATE.atTime(10, 0),
                null, MAPPER.valueToTree(new CalendarPayload("회의", null, null, false)));
        ReflectionTestUtils.setField(item, "timelineItemId", itemId);
        return item;
    }

    /** 소유한 DRAFT record 위의 이벤트 + guard 선점 성공 스텁(공통 성공 경로). */
    private void stubOwnedDraftEventWithGuard() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID, RECORD_ID)));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString())).thenReturn(true);
    }

    /** 삭제 대상 event(집합)의 junction과, 각 후보 Item의 전체 association·엔티티 로드를 스텁한다. */
    private void stubJunction(Collection<Long> deletedEventIds, Map<Long, List<Long>> eventIdsByItemId,
                              List<TimelineItem> loadedExclusiveItems) {
        List<TimelineEventItem> deletedLinks = eventIdsByItemId.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .filter(deletedEventIds::contains)
                        .map(eventId -> TimelineEventItem.of(eventId, entry.getKey())))
                .toList();
        when(timelineEventItemService.findByTimelineEventIds(anyCollection())).thenReturn(deletedLinks);
        List<TimelineEventItem> allLinks = eventIdsByItemId.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(eventId -> TimelineEventItem.of(eventId, entry.getKey())))
                .toList();
        when(timelineEventItemService.findByTimelineItemIds(anyCollection())).thenReturn(allLinks);
        when(timelineItemService.findByIds(anyCollection())).thenReturn(loadedExclusiveItems);
    }

    // --- deleteEvent: 성공 경로 ---

    @Test
    void deleteEvent_deletesExclusivePhotosThenDbAndReleasesGuard_inOrder() {
        stubOwnedDraftEventWithGuard();
        // 21(PHOTO)·22(CALENDAR)·23(PHOTO) 전부 이 event에만 연결 — 모두 exclusive.
        stubJunction(Set.of(EVENT_ID),
                Map.of(21L, List.of(EVENT_ID), 22L, List.of(EVENT_ID), 23L, List.of(EVENT_ID)),
                List.of(photoItem(21L, "a.jpg"), calendarItem(22L), photoItem(23L, "b.jpg")));

        service.deleteEvent(VERSION, USER_ID, EVENT_ID);

        // PHOTO만 full key로 유도된다(CALENDAR 제외). userId는 소유권 검증을 통과한 컨트롤러 결정값.
        verify(s3PhotoStorageService).deleteAll(List.of(
                PhotoObjectKeys.fullKey("a.jpg", USER_ID), PhotoObjectKeys.fullKey("b.jpg", USER_ID)));
        verify(timelineDeletionTransactionService).deleteEvent(USER_ID, EVENT_ID);

        // 순서 고정: guard 선점 → junction 수집(guard 안 — 동시 AI append의 연결 변경을 배제) → S3 → DB → 해제.
        InOrder order = inOrder(timelineTaskService, timelineEventItemService, s3PhotoStorageService,
                timelineDeletionTransactionService);
        order.verify(timelineTaskService).claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
        order.verify(timelineEventItemService).findByTimelineEventIds(anyCollection());
        order.verify(s3PhotoStorageService).deleteAll(anyList());
        order.verify(timelineDeletionTransactionService).deleteEvent(USER_ID, EVENT_ID);
        order.verify(timelineTaskService).releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
    }

    @Test
    void deleteEvent_sharedItemPhoto_isNotDeletedFromS3() {
        // N:M 핵심: 다른 event(12)에도 연결된 shared Item(21)의 PHOTO는 S3에서 지우지 않는다.
        stubOwnedDraftEventWithGuard();
        stubJunction(Set.of(EVENT_ID),
                Map.of(21L, List.of(EVENT_ID, 12L), 23L, List.of(EVENT_ID)),
                List.of(photoItem(23L, "exclusive.jpg")));

        service.deleteEvent(VERSION, USER_ID, EVENT_ID);

        // exclusive(23)만 로드·삭제 — shared(21)는 후보에서 제외된다.
        ArgumentCaptor<Collection<Long>> loadedIds = ArgumentCaptor.forClass(Collection.class);
        verify(timelineItemService).findByIds(loadedIds.capture());
        assertThat(loadedIds.getValue()).containsExactly(23L);
        verify(s3PhotoStorageService).deleteAll(List.of(PhotoObjectKeys.fullKey("exclusive.jpg", USER_ID)));
        verify(timelineDeletionTransactionService).deleteEvent(USER_ID, EVENT_ID);
    }

    @Test
    void deleteEvent_claimsAndReleasesSameDeleteHolder() {
        stubOwnedDraftEventWithGuard();
        stubJunction(Set.of(EVENT_ID), Map.of(), List.of());

        service.deleteEvent(VERSION, USER_ID, EVENT_ID);

        ArgumentCaptor<String> claimed = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> released = ArgumentCaptor.forClass(String.class);
        verify(timelineTaskService).claimDateGuard(eq(USER_ID), eq(RECORD_DATE), claimed.capture());
        verify(timelineTaskService).releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), released.capture());
        // holder는 delete:{operationId}이고 선점·해제가 같은 값이어야 한다(compare-and-release).
        assertThat(claimed.getValue()).startsWith("delete:");
        assertThat(released.getValue()).isEqualTo(claimed.getValue());
    }

    @Test
    void deleteEvent_noPhotos_skipsS3AndDeletesDb() {
        stubOwnedDraftEventWithGuard();
        stubJunction(Set.of(EVENT_ID), Map.of(22L, List.of(EVENT_ID)), List.of(calendarItem(22L)));

        service.deleteEvent(VERSION, USER_ID, EVENT_ID);

        verifyNoInteractions(s3PhotoStorageService);
        verify(timelineDeletionTransactionService).deleteEvent(USER_ID, EVENT_ID);
        verify(timelineTaskService).releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
    }

    @Test
    void deleteEvent_dedupesSameObjectKey() {
        // rawId 중복 허용 정책상 같은 파일을 참조하는 중복 Item이 있어도 S3 삭제는 key당 한 번이다.
        stubOwnedDraftEventWithGuard();
        stubJunction(Set.of(EVENT_ID),
                Map.of(21L, List.of(EVENT_ID), 23L, List.of(EVENT_ID)),
                List.of(photoItem(21L, "same.jpg"), photoItem(23L, "same.jpg")));

        service.deleteEvent(VERSION, USER_ID, EVENT_ID);

        verify(s3PhotoStorageService).deleteAll(List.of(PhotoObjectKeys.fullKey("same.jpg", USER_ID)));
    }

    @Test
    void deleteEvent_skipsUnparseablePayloadAndBlankFilename_andProceeds() {
        stubOwnedDraftEventWithGuard();
        // 배열 payload는 PhotoPayload로 역직렬화 불가(파싱 실패), blank filename은 key 유도 불가 — 둘 다
        // S3만 건너뛰고(orphan 허용, cleanup 스케줄러와 동일 규칙) 삭제는 계속 진행한다.
        TimelineItem broken = TimelineItem.of(ItemType.PHOTO, "raw-31", null, null, MAPPER.createArrayNode());
        ReflectionTestUtils.setField(broken, "timelineItemId", 31L);
        stubJunction(Set.of(EVENT_ID),
                Map.of(31L, List.of(EVENT_ID), 32L, List.of(EVENT_ID), 33L, List.of(EVENT_ID)),
                List.of(broken, photoItem(32L, "  "), photoItem(33L, "good.jpg")));

        service.deleteEvent(VERSION, USER_ID, EVENT_ID);

        verify(s3PhotoStorageService).deleteAll(List.of(PhotoObjectKeys.fullKey("good.jpg", USER_ID)));
        verify(timelineDeletionTransactionService).deleteEvent(USER_ID, EVENT_ID);
    }

    // --- deleteEvent: 사전 검증(부수효과 전 거절) ---

    @Test
    void deleteEvent_hidesUnknownEventAs404() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND);
                    assertThat(ex.getErrorCode()).isEqualTo(-404);
                });
        verifyNoInteractions(timelineTaskService, s3PhotoStorageService, timelineDeletionTransactionService);
    }

    @Test
    void deleteEvent_hidesForeignRecordAs404() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID, RECORD_ID)));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(999L)));

        assertThatThrownBy(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        verifyNoInteractions(timelineTaskService, s3PhotoStorageService, timelineDeletionTransactionService);
    }

    @Test
    void deleteEvent_rejectsSavedRecordWith1003_beforeGuardClaim() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID, RECORD_ID)));
        DailyRecord saved = draftRecordOf(USER_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1003));
        // SAVED 선거절 — guard·S3·DB 어떤 부수효과도 없다.
        verifyNoInteractions(timelineTaskService, s3PhotoStorageService, timelineDeletionTransactionService);
    }

    @Test
    void deleteEvent_guardClaimFailureRejectsWith1016_withoutTouchingGuard() {
        when(timelineEventService.findById(EVENT_ID)).thenReturn(Optional.of(event(EVENT_ID, RECORD_ID)));
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.RECORD_DATE_IN_PROGRESS);
                    assertThat(ex.getErrorCode()).isEqualTo(-1016);
                });
        verifyNoInteractions(s3PhotoStorageService, timelineDeletionTransactionService);
        // 내 guard가 아니므로 해제하지 않는다(남의 lease를 건드리면 안 됨).
        verify(timelineTaskService, never()).releaseDateGuard(anyLong(), any(), anyString());
    }

    // --- deleteEvent: 실패 경로에서도 guard 해제(재시도 수렴의 전제) ---

    @Test
    void deleteEvent_s3FailureSkipsDbDelete_andReleasesGuard() {
        stubOwnedDraftEventWithGuard();
        stubJunction(Set.of(EVENT_ID), Map.of(21L, List.of(EVENT_ID)), List.of(photoItem(21L, "a.jpg")));
        doThrow(new BusinessException(ExceptionType.PHOTO_BATCH_DELETE_FAILED))
                .when(s3PhotoStorageService).deleteAll(anyList());

        assertThatThrownBy(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));

        // S3 실패 시 DB 삭제를 시작하지 않는다(데이터 보존 → 재시도 수렴).
        verifyNoInteractions(timelineDeletionTransactionService);
        // 실패 경로에서도 guard는 해제된다 — 미해제면 재시도가 1시간 1016으로 막힌다.
        verify(timelineTaskService).releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
    }

    @Test
    void deleteEvent_dbFailurePropagates_andReleasesGuard() {
        stubOwnedDraftEventWithGuard();
        stubJunction(Set.of(EVENT_ID), Map.of(), List.of());
        doThrow(new RuntimeException("db down")).when(timelineDeletionTransactionService)
                .deleteEvent(USER_ID, EVENT_ID);

        // S3 성공 후 DB 실패는 500으로 전파된다(재시도 수렴 — 이미 지워진 key는 S3가 성공 처리).
        assertThatThrownBy(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");
        verify(timelineTaskService).releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
    }

    @Test
    void deleteEvent_releaseFailureIsSwallowed_onSuccessPath() {
        stubOwnedDraftEventWithGuard();
        stubJunction(Set.of(EVENT_ID), Map.of(), List.of());
        when(timelineTaskService.releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        // 해제는 best-effort — 실패해도 삭제 성공 결과를 뒤집지 않는다(TTL이 안전망).
        assertThatCode(() -> service.deleteEvent(VERSION, USER_ID, EVENT_ID)).doesNotThrowAnyException();
        verify(timelineDeletionTransactionService).deleteEvent(USER_ID, EVENT_ID);
    }

    // --- deleteDailyRecord ---

    @Test
    void deleteDailyRecord_collectsExclusivePhotosAcrossAllEvents_thenDeletesDb() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString())).thenReturn(true);
        when(timelineEventService.findByDailyRecordId(RECORD_ID))
                .thenReturn(List.of(event(11L, RECORD_ID), event(12L, RECORD_ID)));
        // 21은 11에만, 23은 12에만, 25는 11·12 양쪽(record 안 shared — record 전체 삭제라 exclusive 취급).
        stubJunction(Set.of(11L, 12L),
                Map.of(21L, List.of(11L), 23L, List.of(12L), 25L, List.of(11L, 12L)),
                List.of(photoItem(21L, "a.jpg"), photoItem(23L, "b.jpg"), photoItem(25L, "c.jpg")));

        service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID);

        verify(s3PhotoStorageService).deleteAll(List.of(
                PhotoObjectKeys.fullKey("a.jpg", USER_ID), PhotoObjectKeys.fullKey("b.jpg", USER_ID),
                PhotoObjectKeys.fullKey("c.jpg", USER_ID)));
        verify(timelineDeletionTransactionService).deleteDailyRecord(USER_ID, RECORD_ID);
        verify(timelineTaskService).releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
    }

    @Test
    void deleteDailyRecord_itemLinkedToEventOutsideRecord_isKeptAsShared() {
        // 방어: record 밖 event(99)에도 연결된 후보(21)는 shared로 간주해 S3·DB 삭제 대상에서 제외한다
        // (정상 write 경로에선 same-record 규칙으로 없어야 하는 상태지만, 있어도 데이터를 지키는 쪽으로).
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString())).thenReturn(true);
        when(timelineEventService.findByDailyRecordId(RECORD_ID)).thenReturn(List.of(event(11L, RECORD_ID)));
        stubJunction(Set.of(11L),
                Map.of(21L, List.of(11L, 99L), 22L, List.of(11L)),
                List.of(photoItem(22L, "only-mine.jpg")));

        service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID);

        ArgumentCaptor<Collection<Long>> loadedIds = ArgumentCaptor.forClass(Collection.class);
        verify(timelineItemService).findByIds(loadedIds.capture());
        assertThat(loadedIds.getValue()).containsExactly(22L);
        verify(s3PhotoStorageService).deleteAll(List.of(PhotoObjectKeys.fullKey("only-mine.jpg", USER_ID)));
    }

    @Test
    void deleteDailyRecord_hidesUnknownRecordAs404() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(ex.getErrorCode()).isEqualTo(-404);
                });
        verifyNoInteractions(timelineTaskService, s3PhotoStorageService, timelineDeletionTransactionService);
    }

    @Test
    void deleteDailyRecord_hidesForeignRecordAs404() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(999L)));

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND));
    }

    @Test
    void deleteDailyRecord_rejectsSavedRecordWith1003() {
        DailyRecord saved = draftRecordOf(USER_ID);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1003));
        verifyNoInteractions(timelineTaskService, s3PhotoStorageService, timelineDeletionTransactionService);
    }

    @Test
    void deleteDailyRecord_guardClaimFailureRejectsWith1016() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1016));
        verifyNoInteractions(s3PhotoStorageService, timelineDeletionTransactionService);
    }

    @Test
    void deleteDailyRecord_s3FailureSkipsDbDelete_andReleasesGuard() {
        when(dailyRecordService.findById(RECORD_ID)).thenReturn(Optional.of(draftRecordOf(USER_ID)));
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString())).thenReturn(true);
        when(timelineEventService.findByDailyRecordId(RECORD_ID)).thenReturn(List.of(event(11L, RECORD_ID)));
        stubJunction(Set.of(11L), Map.of(21L, List.of(11L)), List.of(photoItem(21L, "a.jpg")));
        doThrow(new BusinessException(ExceptionType.PHOTO_BATCH_DELETE_FAILED))
                .when(s3PhotoStorageService).deleteAll(anyList());

        assertThatThrownBy(() -> service.deleteDailyRecord(VERSION, USER_ID, RECORD_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        verifyNoInteractions(timelineDeletionTransactionService);
        verify(timelineTaskService).releaseDateGuard(eq(USER_ID), eq(RECORD_DATE), anyString());
    }
}
