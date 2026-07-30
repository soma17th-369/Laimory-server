package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.HealthMetric;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.dto.TimelineWindowDto;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.payload.HealthPayload;
import com.laimory.server.timeline.payload.MovementEndpoint;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.NotificationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.StayPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 * POST 오케스트레이터 단위 검증. 요청 검증·recordDate/window pass-through·SAVED 거절·DailyRecord 선생성
 * ·junction 경유 기존 rawId 제외·보상 삭제·offset window 변환·디스패치 합성. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class TimelineDraftTaskServiceTest {

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineDraftPreparationService timelineDraftPreparationService;
    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;
    @Mock
    private TimelineEventService timelineEventService;
    @Mock
    private TimelineEventItemService timelineEventItemService;
    @Mock
    private TimelineItemService timelineItemService;
    @Mock
    private SourceItemEnrichmentService sourceItemEnrichmentService;
    @Mock
    private TimelineAiDispatcher timelineAiDispatcher;
    @Mock
    private Clock clock;

    private TimelineDraftTaskService service;

    private static final String VERSION = "v1";
    // 인증 principal userId — 모든 귀속 지점(조회·enrich·staging·task)에 이 값 하나만 흘러야 한다.
    private static final long USER_ID = 7L;
    private static final String ZONE = "Asia/Seoul";
    // 클라 선택 날짜(단일 권위) — 서버는 계산 없이 그대로 쓴다.
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    // 실제 작성 시각 메타데이터 — "다음날 아침에 쓴 어제 일기" 시나리오라 DATE와 날짜가 다르다(정합성 미검증 계약).
    private static final LocalDateTime RECORD_AT = LocalDateTime.of(2026, 6, 18, 9, 30);
    // 클라가 계산해 보낸 AI 이벤트 생성 범위(선택 날짜의 달력 하루) — 서버는 pass-through한다.
    private static final TimelineWindowDto WINDOW = new TimelineWindowDto(
            LocalDateTime.of(2026, 6, 17, 0, 0), LocalDateTime.of(2026, 6, 18, 0, 0));
    // PROCESSING 저장 직전 캡처되는 시각(폴링 elapsedSeconds 기준) — mock Clock이 반환한다.
    private static final Instant PROCESSING_STARTED_AT = Instant.parse("2026-06-17T03:05:00Z");
    // 선생성 트랜잭션이 반환하는 record ID — Redis task·AI dispatch body에 실린다.
    private static final long RECORD_ID = 42L;
    // 엄격 검증을 통과하는 유효 filename(UUIDv7 + 허용 ext).
    private static final String VALID_FILENAME = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";

    @BeforeEach
    void setUp() {
        service = new TimelineDraftTaskService(
                dailyRecordService, timelineTaskService, timelineDraftPreparationService,
                timelineDraftSourceItemService, timelineEventService, timelineEventItemService, timelineItemService,
                sourceItemEnrichmentService, timelineAiDispatcher, new ObjectMapper(), clock);
        // 기본 스텁: enrich pass-through(재구성 자체는 SourceItemEnrichmentServiceTest가 검증).
        // 검증 실패 테스트는 enrich까지 도달하지 않으므로 lenient.
        lenient().when(sourceItemEnrichmentService.enrich(anyList(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // 선생성 트랜잭션 기본 스텁: commit 성공 → record ID 반환.
        lenient().when(timelineDraftPreparationService.prepareDraft(anyLong(), any(), any(), anyString(), anyList()))
                .thenReturn(RECORD_ID);
        // PROCESSING 도달 경로에서만 읽히는 시각 스텁 — 호출 횟수는 전용 테스트가 검증한다.
        lenient().when(clock.instant()).thenReturn(PROCESSING_STARTED_AT);
    }

    private List<SourceItemDto> oneSource() {
        return List.of(new SourceItemDto(ItemType.PHOTO, "raw-photo-1", LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new PhotoPayload(VALID_FILENAME, "content://x", 1.0, 2.0, null, null)));
    }

    @Test
    void createDraftTask_happyPath_preparesThenProcessingThenDispatches() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());

        assertThat(taskId).isNotBlank();
        // 선생성 커밋이 반환한 dailyRecordId가 PROCESSING task에 실린다(recordDate/recordAt/zone은 Redis에 없음).
        verify(timelineTaskService).createProcessing(eq(taskId), eq(USER_ID), eq(RECORD_ID), any(), any(),
                eq(PROCESSING_STARTED_AT));

        // 순서 불변식: enrich(저장 전 — AI가 DB에서 직접 읽음) → 선생성+source 저장 커밋 → Redis PROCESSING → dispatch.
        InOrder order = inOrder(sourceItemEnrichmentService, timelineDraftPreparationService,
                timelineTaskService, timelineAiDispatcher);
        order.verify(sourceItemEnrichmentService).enrich(anyList(), anyLong());
        order.verify(timelineDraftPreparationService).prepareDraft(eq(USER_ID), eq(DATE), eq(RECORD_AT), eq(ZONE),
                anyList());
        order.verify(timelineTaskService).createProcessing(eq(taskId), eq(USER_ID), eq(RECORD_ID), any(), any(),
                eq(PROCESSING_STARTED_AT));
        order.verify(timelineAiDispatcher).dispatch(any(AiTimelineDispatchRequest.class));
    }

    @Test
    void createDraftTask_sameDateRequests_areBothDispatchedWithoutAdmissionGate() {
        // 동시 실행의 정합성까지 보장하는 테스트는 아니다. 같은 날짜 PROCESSING task가 있다는 이유만으로
        // 다음 요청을 사전 거절하지 않는 #211의 admission 계약만 고정한다.
        DailyRecord draft = DailyRecord.createDraft(USER_ID, DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(draft, "dailyRecordId", RECORD_ID);
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE))
                .thenReturn(Optional.empty(), Optional.of(draft));

        String firstTaskId =
                service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());
        String secondTaskId =
                service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());

        assertThat(firstTaskId).isNotEqualTo(secondTaskId);
        verify(timelineDraftPreparationService, times(2))
                .prepareDraft(eq(USER_ID), eq(DATE), eq(RECORD_AT), eq(ZONE), anyList());
        verify(timelineTaskService, times(2))
                .createProcessing(anyString(), eq(USER_ID), eq(RECORD_ID), any(), any(),
                        eq(PROCESSING_STARTED_AT));
        verify(timelineAiDispatcher, times(2)).dispatch(any(AiTimelineDispatchRequest.class));
    }

    @Test
    void createDraftTask_dispatchBody_containsTaskIdRecordIdAndOffsetWindow() {
        // AI 접수 body 계약: taskId·원문 token·dailyRecordId·record timezone 기반 offset window.
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());

        ArgumentCaptor<AiTimelineDispatchRequest> requestCaptor =
                ArgumentCaptor.forClass(AiTimelineDispatchRequest.class);
        verify(timelineAiDispatcher).dispatch(requestCaptor.capture());
        AiTimelineDispatchRequest request = requestCaptor.getValue();
        assertThat(request.taskId()).isEqualTo(taskId);
        assertThat(request.callbackToken()).isNotBlank();
        assertThat(request.dailyRecordId()).isEqualTo(RECORD_ID);
        // Asia/Seoul(+09:00) 변환 — wall-clock은 유지되고 offset만 붙는다.
        assertThat(request.window().startAt())
                .isEqualTo(OffsetDateTime.of(WINDOW.startTime(), ZoneOffset.ofHours(9)));
        assertThat(request.window().endAt())
                .isEqualTo(OffsetDateTime.of(WINDOW.endTime(), ZoneOffset.ofHours(9)));
    }

    @Test
    void createDraftTask_storesOnlyTokenHash_notRawToken() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());

        // Redis에는 단일 token hash, AI dispatch body에는 원문이 전달돼야 한다.
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineTaskService).createProcessing(eq(taskId), eq(USER_ID), eq(RECORD_ID), any(),
                hashCaptor.capture(), any());
        ArgumentCaptor<AiTimelineDispatchRequest> requestCaptor =
                ArgumentCaptor.forClass(AiTimelineDispatchRequest.class);
        verify(timelineAiDispatcher).dispatch(requestCaptor.capture());

        String stored = hashCaptor.getValue();
        String dispatchedToken = requestCaptor.getValue().callbackToken();
        assertThat(dispatchedToken).isNotBlank();
        assertThat(stored).isEqualTo(TaskTokens.hash(dispatchedToken));
        assertThat(stored).isNotEqualTo(dispatchedToken);
    }

    @Test
    void createDraftTask_buildsDraftRowsFromSources_forPreparation() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineDraftSourceItem>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineDraftPreparationService).prepareDraft(eq(USER_ID), eq(DATE), eq(RECORD_AT), eq(ZONE),
                rowsCaptor.capture());
        List<TimelineDraftSourceItem> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(1);
        TimelineDraftSourceItem row = rows.get(0);
        assertThat(row.getTaskId()).isEqualTo(taskId);
        assertThat(row.getUserId()).isEqualTo(USER_ID);
        assertThat(row.getItemType()).isEqualTo(ItemType.PHOTO);
        // rawId는 envelope 필드 — 컬럼으로 그대로 저장된다.
        assertThat(row.getRawId()).isEqualTo("raw-photo-1");
        assertThat(row.getStartAt()).isEqualTo(LocalDateTime.of(2026, 6, 17, 9, 0));
        // payload는 discriminator 없는 raw JsonNode.
        assertThat(row.getPayload().get("filename").asText()).isEqualTo(VALID_FILENAME);
        assertThat(row.getPayload().get("clientPhotoUri").asText()).isEqualTo("content://x");
        assertThat(row.getPayload().has("itemType")).isFalse();
    }

    @Test
    void createDraftTask_reusesDraftRecord_doesNotReject() {
        DailyRecord draft = DailyRecord.createDraft(USER_ID, DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(draft, "dailyRecordId", 3L);
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.of(draft));

        String taskId = service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());

        assertThat(taskId).isNotBlank();
        verify(timelineTaskService).createProcessing(eq(taskId), eq(USER_ID), eq(RECORD_ID), any(), any(),
                eq(PROCESSING_STARTED_AT));
    }

    @Test
    void createDraftTask_rejectsSavedRecord() {
        DailyRecord saved = DailyRecord.createDraft(USER_ID, DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(saved, "dailyRecordId", 5L);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1003));
        verify(timelineDraftPreparationService, never()).prepareDraft(anyLong(), any(), any(), anyString(), anyList());
        verify(timelineTaskService, never()).createProcessing(anyString(), anyLong(), anyLong(), any(), any(), any());
        verify(timelineAiDispatcher, never()).dispatch(any());
    }

    @Test
    void createDraftTask_whenRedisFails_compensatesByDeletingSourcesButKeepsRecord() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("redis down"))
                .when(timelineTaskService).createProcessing(anyString(), anyLong(), anyLong(), any(), any(), any());

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("redis down");

        // 보상 삭제: 이번 task의 source rows만 지운다. DailyRecord는 유지 — 이번 task가 처음 만든 record인지
        // durable하게 알 수 없고, empty DRAFT 재사용이 안전하다. dispatch는 호출되지 않는다.
        verify(timelineDraftSourceItemService).deleteByTaskId(anyString());
        verify(dailyRecordService, never()).deleteById(anyLong());
        verify(timelineAiDispatcher, never()).dispatch(any());
    }

    @Test
    void createDraftTask_whenDispatchRejected_marksFailedAndFailsWith1009() {
        // 미접수 확정(dispatcher가 TimelineAiDispatchRejectedException) — AI가 접수·write하지 않았음이 확실하므로
        // FAILED(24h) 종결을 시도하고, POST는 성공 202로 위장하지 않고 502(-1009)로 실패한다.
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        doThrow(new TimelineAiDispatchRejectedException("4xx", null)).when(timelineAiDispatcher).dispatch(any());

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1009));

        // FAILED는 dispatch에 실었던 그 server taskId로 저장한다. raw 메시지는 저장하지 않는다 — 분류 코드만(상세는 로그로).
        ArgumentCaptor<AiTimelineDispatchRequest> requestCaptor =
                ArgumentCaptor.forClass(AiTimelineDispatchRequest.class);
        verify(timelineAiDispatcher).dispatch(requestCaptor.capture());
        verify(timelineTaskService).markFailed(eq(requestCaptor.getValue().taskId()), eq(USER_ID), eq(RECORD_ID),
                eq(ExceptionType.AI_DISPATCH_FAILED),
                any());
        // draft는 보존한다(cleanup이 정리). 보상 삭제 없음.
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
    }

    @Test
    void createDraftTask_whenDispatchRejectedAndMarkFailedFails_stillFailsWith1009_withoutRetry() {
        // FAILED 저장까지 실패해도 500으로 뒤집히지 않는다 — read-back·재저장·retry 없이 같은 502(-1009)로 끝낸다.
        // task는 PROCESSING으로 남을 수 있고 그 경우 최초 TTL이 회수한다.
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        doThrow(new TimelineAiDispatchRejectedException("4xx", null)).when(timelineAiDispatcher).dispatch(any());
        doThrow(new RuntimeException("redis down")).when(timelineTaskService)
                .markFailed(anyString(), anyLong(), anyLong(), any(), any());

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1009));

        verify(timelineTaskService, times(1)).markFailed(anyString(), anyLong(), anyLong(), any(), any());
        verify(timelineTaskService, times(1))
                .createProcessing(anyString(), anyLong(), anyLong(), any(), any(), any());
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
    }

    @Test
    void createDraftTask_whenDispatchOutcomeUnknown_keepsProcessingAndFailsWith1009() {
        // UNKNOWN(read timeout·5xx·계약 불일치 — Rejected가 아닌 예외) — AI가 이미 접수해 final write를 진행
        // 중일 수 있으므로 FAILED로 덮거나 재저장(TTL 연장)하지 않고 기존 PROCESSING을 유지한 채
        // 502(-1009)로 실패한다(AI callback 또는 TTL 만료가 종결).
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("read timeout")).when(timelineAiDispatcher).dispatch(any());

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1009));

        // 종결·재저장 없음: createProcessing은 최초 1회 그대로, markFailed 미호출.
        verify(timelineTaskService, times(1))
                .createProcessing(anyString(), anyLong(), anyLong(), any(), any(), any());
        verify(timelineTaskService, never()).markFailed(anyString(), anyLong(), anyLong(), any(), any());
        // draft·record 모두 보존(보상 삭제 없음).
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
    }

    @Test
    void createDraftTask_whenEnrichFails_propagates1014AndSavesNothing() {
        // 지오코딩 loud fail: enrich가 BusinessException(1014)을 던지면 그대로 전파(502)되고,
        // 선생성·PROCESSING·dispatch 前이라 저장물이 없다(롤백 불필요).
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        when(sourceItemEnrichmentService.enrich(anyList(), anyLong()))
                .thenThrow(new BusinessException(ExceptionType.GEOCODING_TRANSIENT_FAILURE));

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1014));
        verify(timelineDraftPreparationService, never()).prepareDraft(anyLong(), any(), any(), anyString(), anyList());
        verify(timelineTaskService, never()).createProcessing(anyString(), anyLong(), anyLong(), any(), any(), any());
        verify(timelineAiDispatcher, never()).dispatch(any());
    }

    @Test
    void createDraftTask_whenPreparationFails_propagates() {
        // 선생성 트랜잭션 실패(SAVED 재확인·DB 오류)는 전체 롤백 후 전파한다.
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        when(timelineDraftPreparationService.prepareDraft(anyLong(), any(), any(), anyString(), anyList()))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOf(RuntimeException.class);
        verify(timelineTaskService, never()).createProcessing(anyString(), anyLong(), anyLong(), any(), any(), any());
        // 트랜잭션이 롤백됐으므로 보상 삭제도 없다(지울 게 없음).
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
    }

    @Test
    void createDraftTask_rejectsNullRecordDate() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, null, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullRecordAt() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, null, ZONE, WINDOW, oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullRecordTimeZone() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, null, WINDOW, oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullTimelineWindow() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, null, oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsWindowMissingStartOrEnd() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE,
                new TimelineWindowDto(null, LocalDateTime.of(2026, 6, 18, 0, 0)), oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE,
                new TimelineWindowDto(LocalDateTime.of(2026, 6, 17, 0, 0), null), oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(sourceItemEnrichmentService, never()).enrich(anyList(), anyLong());
        verify(timelineAiDispatcher, never()).dispatch(any());
    }

    @Test
    void createDraftTask_rejectsWindowStartNotBeforeEnd() {
        // start == end, start > end 모두 400 — 서버가 보정(floor/swap)하지 않고 거절한다.
        LocalDateTime point = LocalDateTime.of(2026, 6, 17, 12, 0);
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE,
                new TimelineWindowDto(point, point), oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE,
                new TimelineWindowDto(point, point.minusHours(1)), oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsEmptySourceItems() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullItemType() {
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(null, "r", null, null, new PhotoPayload("u", "content://x", 1.0, 2.0, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullSourceItemElement() {
        // sourceItems 배열에 null 원소([null])가 오면 NPE 500이 아니라 400으로 거절한다.
        List<SourceItemDto> sources = java.util.Collections.singletonList(null);
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsMissingOrTooLongRawId() {
        // rawId는 전 타입 공통 필수(envelope 필드). blank → 400, DB 컬럼(36자) 초과 → 400(저장 전).
        List<SourceItemDto> blankRawId = List.of(new SourceItemDto(
                ItemType.PHOTO, " ", LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new PhotoPayload(VALID_FILENAME, "content://x", 1.0, 2.0, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, blankRawId))
                .isInstanceOf(IllegalArgumentException.class);

        List<SourceItemDto> tooLongRawId = List.of(new SourceItemDto(
                ItemType.PHOTO, "x".repeat(37), LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new PhotoPayload(VALID_FILENAME, "content://x", 1.0, 2.0, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, tooLongRawId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullPayload() {
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.PHOTO, "r", null, null, null));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsInvalidPhotoFilename() {
        // PHOTO filename이 UUIDv7+허용ext 패턴이 아니면 입력 경계에서 400으로 막는다(저장 전).
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.PHOTO, "r", LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new PhotoPayload("../etc/passwd", "content://x", 1.0, 2.0, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsMissingClientPhotoUri() {
        // clientPhotoUri는 1차 로컬 캐싱용이라 PHOTO엔 필수다(누락/blank → 400, 저장 전).
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.PHOTO, "r", LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new PhotoPayload(VALID_FILENAME, null, 1.0, 2.0, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsHealthMissingMetricOrValue() {
        // HEALTH는 metric/value 둘 다 필수(누락 → 400, 저장 전). value는 단위 포함 텍스트라 blank도 누락으로 본다.
        List<SourceItemDto> missingMetric = List.of(new SourceItemDto(
                ItemType.HEALTH, "r", LocalDateTime.of(2026, 6, 17, 0, 0), null,
                new HealthPayload(null, "10145보")));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, missingMetric))
                .isInstanceOf(IllegalArgumentException.class);

        List<SourceItemDto> missingValue = List.of(new SourceItemDto(
                ItemType.HEALTH, "r", LocalDateTime.of(2026, 6, 17, 0, 0), null,
                new HealthPayload(HealthMetric.SLEEP, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, missingValue))
                .isInstanceOf(IllegalArgumentException.class);

        List<SourceItemDto> blankValue = List.of(new SourceItemDto(
                ItemType.HEALTH, "r", LocalDateTime.of(2026, 6, 17, 0, 0), null,
                new HealthPayload(HealthMetric.STEPS, " ")));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, blankValue))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNotificationWithoutTitleAndText() {
        // title/text 둘 다 blank면 NON_NULL 직렬화로 빈 payload가 저장되므로 입력 경계에서 400으로 막는다.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.NOTIFICATION, "r", LocalDateTime.of(2026, 6, 17, 21, 12), null,
                new NotificationPayload("카카오톡", null, " ")));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsStayMissingCoordinate() {
        // STAY 좌표는 필수(지오코딩 enrich 전제) — 누락 → 400, 저장 전.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.STAY, "r", LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new StayPayload(null, 127.0557, null, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNonFiniteCoordinate() {
        // NaN은 범위 비교(-90~90)를 전부 통과하므로 isFinite 검증이 별도로 막아야 한다.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.STAY, "r", LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new StayPayload(Double.NaN, 127.0557, null, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsOutOfRangeCoordinate() {
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.STAY, "r", LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new StayPayload(37.5445, 180.5, null, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsMovementMissingEndpoint() {
        // MOVEMENT는 start/end 객체(각 좌표 포함)가 필수다.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.MOVEMENT, "r", LocalDateTime.of(2026, 6, 17, 8, 30), null,
                new MovementPayload(null, endpoint(37.5445, 127.0557), "IN_VEHICLE", null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNegativeDistanceMeters() {
        // 이동 거리는 음수가 무의미(HEALTH value 음수 거절과 같은 입력 경계 정책).
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.MOVEMENT, "r", LocalDateTime.of(2026, 6, 17, 8, 30), null,
                new MovementPayload(endpoint(37.4979, 127.0276), endpoint(37.5445, 127.0557),
                        "IN_VEHICLE", -1.0)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsMismatchedItemTypeAndPayload() {
        // HTTP 경로는 Jackson 디스크리미네이터가 일치를 보장하지만, 프로그래밍 방식 생성 경로를 방어한다.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.STAY, "r", LocalDateTime.of(2026, 6, 17, 8, 30), null,
                new MovementPayload(endpoint(37.4979, 127.0276), endpoint(37.5445, 127.0557),
                        "IN_VEHICLE", null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_buildsRowsFromEnrichedItems_notRawInput() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        // enrich(재구성) 결과가 저장본이다 — 원본이 아니라 반환 리스트로 row를 빌드해야 한다.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.STAY, "raw-loc-1", LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new StayPayload(37.5340, 126.9668, null, null, null)));
        List<SourceItemDto> enriched = List.of(new SourceItemDto(
                ItemType.STAY, "raw-loc-1", LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new StayPayload(37.5340, 126.9668,
                        "서울 용산구 청파로20길 95", List.of("서울드래곤시티", "그랑씨엘"), "1시간45분")));
        when(sourceItemEnrichmentService.enrich(sources, USER_ID)).thenReturn(enriched);

        service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineDraftSourceItem>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineDraftPreparationService).prepareDraft(eq(USER_ID), eq(DATE), eq(RECORD_AT), eq(ZONE),
                rowsCaptor.capture());
        // enrich 재구성본의 rawId가 그대로 row에 저장된다(envelope 보존).
        assertThat(rowsCaptor.getValue().get(0).getRawId()).isEqualTo("raw-loc-1");
        assertThat(rowsCaptor.getValue().get(0).getPayload().get("address").asText())
                .isEqualTo("서울 용산구 청파로20길 95");
        assertThat(rowsCaptor.getValue().get(0).getPayload().get("places").size()).isEqualTo(2);
        assertThat(rowsCaptor.getValue().get(0).getPayload().get("durationText").asText()).isEqualTo("1시간45분");
    }

    private static MovementEndpoint endpoint(double latitude, double longitude) {
        return new MovementEndpoint(latitude, longitude, null, null);
    }

    @Test
    void createDraftTask_excludesAlreadySavedRawIds_viaJunctionPath() {
        // 기존 DRAFT record의 Event→junction→Item 경로에 raw-photo-1이 이미 저장됨 → 신규 raw-photo-2만 enrich된다.
        DailyRecord draft = DailyRecord.createDraft(USER_ID, DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(draft, "dailyRecordId", 7L);
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.of(draft));
        TimelineEvent event = TimelineEvent.of(7L, TimelineEventType.UNKNOWN, RECORD_AT, null, "t", null);
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        when(timelineEventService.findByDailyRecordId(7L)).thenReturn(List.of(event));
        when(timelineEventItemService.findByTimelineEventIds(List.of(11L)))
                .thenReturn(List.of(TimelineEventItem.of(11L, 21L)));
        when(timelineItemService.findSavedRawIds(eq(List.of(21L)), anyList())).thenReturn(Set.of("raw-photo-1"));

        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.PHOTO, "raw-photo-1", LocalDateTime.of(2026, 6, 17, 9, 0), null,
                        new PhotoPayload(VALID_FILENAME, "content://x", 1.0, 2.0, null, null)),
                new SourceItemDto(ItemType.PHOTO, "raw-photo-2", LocalDateTime.of(2026, 6, 17, 10, 0), null,
                        new PhotoPayload(VALID_FILENAME, "content://y", 1.0, 2.0, null, null)));

        service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SourceItemDto>> enrichCaptor = ArgumentCaptor.forClass(List.class);
        verify(sourceItemEnrichmentService).enrich(enrichCaptor.capture(), eq(USER_ID));
        assertThat(enrichCaptor.getValue()).extracting(SourceItemDto::rawId).containsExactly("raw-photo-2");
    }

    @Test
    void createDraftTask_allItemsAlreadySaved_rejectsWith1013() {
        // 요청의 모든 rawId가 이미 저장됨 → 신규 0 → ERROR_1013, 저장·dispatch 없음.
        DailyRecord draft = DailyRecord.createDraft(USER_ID, DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(draft, "dailyRecordId", 7L);
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.of(draft));
        TimelineEvent event = TimelineEvent.of(7L, TimelineEventType.UNKNOWN, RECORD_AT, null, "t", null);
        ReflectionTestUtils.setField(event, "timelineEventId", 11L);
        when(timelineEventService.findByDailyRecordId(7L)).thenReturn(List.of(event));
        when(timelineEventItemService.findByTimelineEventIds(List.of(11L)))
                .thenReturn(List.of(TimelineEventItem.of(11L, 21L)));
        when(timelineItemService.findSavedRawIds(eq(List.of(21L)), anyList())).thenReturn(Set.of("raw-photo-1"));

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1013));
        verify(timelineDraftPreparationService, never()).prepareDraft(anyLong(), any(), any(), anyString(), anyList());
        verify(timelineTaskService, never()).createProcessing(anyString(), anyLong(), anyLong(), any(), any(), any());
        verify(timelineAiDispatcher, never()).dispatch(any());
    }

    @Test
    void createDraftTask_dedupesDuplicateRawIdInBatch_savesFirstOnly() {
        // 같은 rawId 2개 → 첫 항목만 유지(9:00), 배치 내 dedupe.
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.PHOTO, "dup", LocalDateTime.of(2026, 6, 17, 9, 0), null,
                        new PhotoPayload(VALID_FILENAME, "content://x", 1.0, 2.0, null, null)),
                new SourceItemDto(ItemType.PHOTO, "dup", LocalDateTime.of(2026, 6, 17, 10, 0), null,
                        new PhotoPayload(VALID_FILENAME, "content://y", 1.0, 2.0, null, null)));

        service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineDraftSourceItem>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineDraftPreparationService).prepareDraft(eq(USER_ID), eq(DATE), eq(RECORD_AT), eq(ZONE),
                rowsCaptor.capture());
        assertThat(rowsCaptor.getValue()).hasSize(1);
        assertThat(rowsCaptor.getValue().get(0).getStartAt()).isEqualTo(LocalDateTime.of(2026, 6, 17, 9, 0));
    }

    @Test
    void createDraftTask_passesRequestWindowThrough_evenWhenItemMinMaxDiffers() {
        // 신규 item은 9:00~21:00이지만 요청 window(달력 하루)가 그대로 Redis에 전달된다 — min/max 재계산·보정 없음.
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.HEALTH, "h-1", LocalDateTime.of(2026, 6, 17, 9, 0), LocalDateTime.of(2026, 6, 17, 21, 0),
                new HealthPayload(HealthMetric.STEPS, "100보")));

        service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources);

        ArgumentCaptor<TimelineDraftTask.TimelineWindow> windowCaptor =
                ArgumentCaptor.forClass(TimelineDraftTask.TimelineWindow.class);
        verify(timelineTaskService).createProcessing(anyString(), eq(USER_ID), eq(RECORD_ID),
                windowCaptor.capture(), any(), any());
        assertThat(windowCaptor.getValue().startTime()).isEqualTo(WINDOW.startTime());
        assertThat(windowCaptor.getValue().endTime()).isEqualTo(WINDOW.endTime());
    }

    @Test
    void createDraftTask_allowsNonCalendarWindow_andDateMismatchWithRecordDate() {
        // 서버는 calendar-day shape도, recordDate와의 날짜 정합성도 재검증하지 않는다 —
        // start < end면 자정 gap(01:00 시작)·부분 하루·다른 날짜 window도 그대로 통과한다.
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        TimelineWindowDto partial = new TimelineWindowDto(
                LocalDateTime.of(2026, 6, 18, 1, 0), LocalDateTime.of(2026, 6, 18, 13, 30));

        service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, partial, oneSource());

        ArgumentCaptor<TimelineDraftTask.TimelineWindow> windowCaptor =
                ArgumentCaptor.forClass(TimelineDraftTask.TimelineWindow.class);
        verify(timelineTaskService).createProcessing(anyString(), eq(USER_ID), eq(RECORD_ID),
                windowCaptor.capture(), any(), any());
        assertThat(windowCaptor.getValue().startTime()).isEqualTo(partial.startTime());
        assertThat(windowCaptor.getValue().endTime()).isEqualTo(partial.endTime());
    }

    @Test
    void createDraftTask_recordDateIndependentOfRecordAt() {
        // recordAt이 선택 날짜(6/17)와 무관한 다음날 오후여도 recordDate는 요청값 그대로다 —
        // 조회와 선생성 모두 요청 recordDate 기준으로 흐른다(소급 기록 회귀 방지).
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        LocalDateTime nextDayAfternoon = LocalDateTime.of(2026, 6, 18, 15, 0);

        service.createDraftTask(VERSION, USER_ID, DATE, nextDayAfternoon, ZONE, WINDOW, oneSource());

        verify(dailyRecordService).findByUserIdAndRecordDate(USER_ID, DATE);
        verify(timelineDraftPreparationService).prepareDraft(eq(USER_ID), eq(DATE), eq(nextDayAfternoon), eq(ZONE),
                anyList());
    }

    @Test
    void createDraftTask_capturesProcessingStartedAtOnce_afterPreparationBeforeProcessing() {
        // Clock은 PROCESSING 도달 경로에서 딱 한 번, 선생성 커밋 후 PROCESSING 저장 전에 읽힌다 —
        // 전처리(검증·enrich·선생성) 시간을 제외한 "AI 작업 대기 시작" 경계.
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());

        verify(clock, times(1)).instant();
        InOrder order = inOrder(timelineDraftPreparationService, clock, timelineTaskService);
        order.verify(timelineDraftPreparationService).prepareDraft(anyLong(), any(), any(), anyString(), anyList());
        order.verify(clock).instant();
        order.verify(timelineTaskService).createProcessing(eq(taskId), eq(USER_ID), eq(RECORD_ID), any(), any(),
                eq(PROCESSING_STARTED_AT));
    }

    @Test
    void createDraftTask_savedRecordRejection_doesNotReadClock() {
        // PROCESSING에 도달하지 않는 SAVED 거절에서는 시각을 캡처하지 않는다.
        DailyRecord saved = DailyRecord.createDraft(USER_ID, DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1003));
        verify(clock, never()).instant();
    }

    @Test
    void createDraftTask_preparationFailure_doesNotReadClock() {
        // 캡처는 선생성 커밋 성공 직후다 — prepareDraft가 던지면 시각을 읽지 않는다(경계 고정).
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        when(timelineDraftPreparationService.prepareDraft(anyLong(), any(), any(), anyString(), anyList()))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOf(RuntimeException.class);
        verify(clock, never()).instant();
    }
}
