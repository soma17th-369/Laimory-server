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
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.CallbackTokens;
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
    // 인증 principal userId — 모든 귀속 지점(조회·guard·enrich·staging·task)에 이 값 하나만 흘러야 한다.
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
        // 날짜 guard 기본 스텁: 선점·재갱신 성공. guard 거절/해제 경계는 전용 테스트가 검증한다.
        lenient().when(timelineTaskService.claimDateGuard(anyLong(), any(), anyString())).thenReturn(true);
        lenient().when(timelineTaskService.refreshDateGuard(anyLong(), any(), anyString())).thenReturn(true);
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
        verify(timelineTaskService).createProcessing(eq(taskId), eq(USER_ID), eq(RECORD_ID), any(), anyString(),
                eq(PROCESSING_STARTED_AT));

        // 순서 불변식: enrich(저장 전 — AI가 DB에서 직접 읽음) → 선생성+source 저장 커밋 → Redis PROCESSING → dispatch.
        InOrder order = inOrder(sourceItemEnrichmentService, timelineDraftPreparationService,
                timelineTaskService, timelineAiDispatcher);
        order.verify(sourceItemEnrichmentService).enrich(anyList(), anyLong());
        order.verify(timelineDraftPreparationService).prepareDraft(eq(USER_ID), eq(DATE), eq(RECORD_AT), eq(ZONE),
                anyList());
        order.verify(timelineTaskService).createProcessing(eq(taskId), eq(USER_ID), eq(RECORD_ID), any(), anyString(),
                eq(PROCESSING_STARTED_AT));
        order.verify(timelineAiDispatcher).dispatch(any(AiTimelineDispatchRequest.class));
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

        // Redis에 저장되는 값(createProcessing 인자)은 해시, AI에 전달되는 값(dispatch body)은 원문이어야 한다.
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineTaskService).createProcessing(eq(taskId), eq(USER_ID), eq(RECORD_ID), any(),
                hashCaptor.capture(), any());
        ArgumentCaptor<AiTimelineDispatchRequest> requestCaptor =
                ArgumentCaptor.forClass(AiTimelineDispatchRequest.class);
        verify(timelineAiDispatcher).dispatch(requestCaptor.capture());

        String storedHash = hashCaptor.getValue();
        String dispatchedToken = requestCaptor.getValue().callbackToken();
        assertThat(dispatchedToken).isNotBlank();
        assertThat(storedHash).isNotEqualTo(dispatchedToken);
        assertThat(storedHash).isEqualTo(CallbackTokens.hash(dispatchedToken));
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
        verify(timelineTaskService).createProcessing(eq(taskId), eq(USER_ID), eq(RECORD_ID), any(), anyString(),
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
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1003));
        verify(timelineDraftPreparationService, never()).prepareDraft(anyLong(), any(), any(), anyString(), anyList());
        verify(timelineTaskService, never()).createProcessing(anyString(), anyLong(), anyLong(), any(), anyString(), any());
        verify(timelineAiDispatcher, never()).dispatch(any());
        // 해제 경계 규칙 ①: PROCESSING 저장 전 실패(SAVED 거절)는 자신이 선점한 guard(동일 holder)를 즉시 해제한다.
        ArgumentCaptor<String> holderCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineTaskService).claimDateGuard(eq(USER_ID), eq(DATE), holderCaptor.capture());
        assertThat(holderCaptor.getValue()).startsWith("task:");
        verify(timelineTaskService).releaseDateGuard(USER_ID, DATE, holderCaptor.getValue());
    }

    @Test
    void createDraftTask_whenRedisFails_compensatesByDeletingSourcesButKeepsRecord() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("redis down"))
                .when(timelineTaskService).createProcessing(anyString(), anyLong(), anyLong(), any(), anyString(), any());

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("redis down");

        // 보상 삭제: 이번 task의 source rows만 지운다. DailyRecord는 유지 — 이번 task가 처음 만든 record인지
        // durable하게 알 수 없고, empty DRAFT 재사용이 안전하다. dispatch는 호출되지 않는다.
        verify(timelineDraftSourceItemService).deleteByTaskId(anyString());
        verify(dailyRecordService, never()).deleteById(anyLong());
        verify(timelineAiDispatcher, never()).dispatch(any());
        // 규칙 ①: PROCESSING 저장 실패도 보상 후 자신의 guard를 해제해 즉시 재시도가 가능해야 한다.
        verify(timelineTaskService).releaseDateGuard(eq(USER_ID), eq(DATE), anyString());
    }

    @Test
    void createDraftTask_whenDispatchThrows_marksFailedKeepsDraftsAndReturnsTaskId() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("boom")).when(timelineAiDispatcher).dispatch(any());

        // dispatch가 동기 예외를 던져도 taskId는 반환되고 task는 FAILED로 고정된다(고아 PROCESSING 방지).
        String taskId = service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());

        assertThat(taskId).isNotBlank();
        // raw 메시지("boom")는 저장하지 않는다 — 분류 코드만(상세는 로그로).
        verify(timelineTaskService).markFailed(eq(taskId), eq(USER_ID), eq(RECORD_ID), eq(ErrorCode.ERROR_1009),
                anyString());
        // dispatch 실패는 draft를 보존한다(cleanup이 정리). 보상 삭제 없음.
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
        // 규칙 ③: FAILED terminal 저장 성공 후 guard를 해제한다(순서: markFailed → release).
        InOrder order = inOrder(timelineTaskService);
        order.verify(timelineTaskService).markFailed(eq(taskId), eq(USER_ID), eq(RECORD_ID), eq(ErrorCode.ERROR_1009),
                anyString());
        order.verify(timelineTaskService).releaseDateGuard(USER_ID, DATE, "task:" + taskId);
    }

    @Test
    void createDraftTask_whenEnrichFails_propagates1014AndSavesNothing() {
        // 지오코딩 loud fail: enrich가 BusinessException(1014)을 던지면 그대로 전파(502)되고,
        // 선생성·PROCESSING·dispatch 前이라 저장물이 없다(롤백 불필요) — 선점한 guard만 해제한다.
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        when(sourceItemEnrichmentService.enrich(anyList(), anyLong()))
                .thenThrow(new BusinessException(ExceptionType.GEOCODING_TRANSIENT_FAILURE));

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1014));
        verify(timelineDraftPreparationService, never()).prepareDraft(anyLong(), any(), any(), anyString(), anyList());
        verify(timelineTaskService, never()).createProcessing(anyString(), anyLong(), anyLong(), any(), anyString(), any());
        verify(timelineAiDispatcher, never()).dispatch(any());
        // 규칙 ①: 선처리(enrich) 실패도 자신의 guard를 즉시 해제한다.
        verify(timelineTaskService).releaseDateGuard(eq(USER_ID), eq(DATE), anyString());
    }

    @Test
    void createDraftTask_whenPreparationFails_propagatesAndReleasesGuard() {
        // 선생성 트랜잭션 실패(SAVED 재확인·DB 오류)는 전체 롤백 후 전파 — guard만 해제하면 재시도 가능하다.
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        when(timelineDraftPreparationService.prepareDraft(anyLong(), any(), any(), anyString(), anyList()))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOf(RuntimeException.class);
        verify(timelineTaskService, never()).createProcessing(anyString(), anyLong(), anyLong(), any(), anyString(), any());
        // 트랜잭션이 롤백됐으므로 보상 삭제도 없다(지울 게 없음).
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
        verify(timelineTaskService).releaseDateGuard(eq(USER_ID), eq(DATE), anyString());
    }

    @Test
    void createDraftTask_guardClaimFails_rejectsWith1016_beforeAnyProcessing() {
        // 같은 날짜에 진행 중인 draft/삭제가 있으면(SET NX 실패) 409(ERROR_1016)로 즉시 거절한다.
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(DATE), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1016));
        // 선점 실패 = 남의 guard다 — 해제하면 안 되고, 어떤 처리(조회·enrich·저장·dispatch)도 시작하지 않는다.
        verify(timelineTaskService, never()).releaseDateGuard(anyLong(), any(), anyString());
        verify(dailyRecordService, never()).findByUserIdAndRecordDate(anyLong(), any());
        verify(sourceItemEnrichmentService, never()).enrich(anyList(), anyLong());
        verify(timelineDraftPreparationService, never()).prepareDraft(anyLong(), any(), any(), anyString(), anyList());
        verify(timelineAiDispatcher, never()).dispatch(any());
    }

    @Test
    void createDraftTask_happyPath_refreshConfirmsOwnershipBeforeDispatch_andKeepsGuardHeld() {
        // refresh(소유 재확인+TTL 정렬)가 반드시 dispatch보다 앞선다 — 소유 확인 없이 AI 작업이 나가지 않는다.
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());

        InOrder order = inOrder(timelineTaskService, timelineAiDispatcher);
        order.verify(timelineTaskService).claimDateGuard(USER_ID, DATE, "task:" + taskId);
        order.verify(timelineTaskService).createProcessing(eq(taskId), eq(USER_ID), eq(RECORD_ID), any(), anyString(),
                eq(PROCESSING_STARTED_AT));
        order.verify(timelineTaskService).refreshDateGuard(USER_ID, DATE, "task:" + taskId);
        order.verify(timelineAiDispatcher).dispatch(any(AiTimelineDispatchRequest.class));
        verify(timelineTaskService, never()).releaseDateGuard(anyLong(), any(), anyString());
    }

    @Test
    void createDraftTask_guardOwnershipLostBeforeDispatch_marksFailedWithoutDispatch() {
        // refresh=false = 내 lease 만료 후 다른 작업(draft/삭제)이 같은 날짜를 선점했을 수 있음.
        // 날짜당 작업 하나 불변식을 지키기 위해 dispatch하지 않고 FAILED(1009)로 종결한다(클라는 폴링 후 재시도).
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        when(timelineTaskService.refreshDateGuard(anyLong(), any(), anyString())).thenReturn(false);

        String taskId = service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());

        assertThat(taskId).isNotBlank();
        verify(timelineAiDispatcher, never()).dispatch(any());
        verify(timelineTaskService).markFailed(eq(taskId), eq(USER_ID), eq(RECORD_ID), eq(ErrorCode.ERROR_1009),
                anyString());
        // 내 lease가 아님이 확정된 상태 — 해제(남의 guard 훼손 가능 경로)도 하지 않는다.
        verify(timelineTaskService, never()).releaseDateGuard(anyLong(), any(), anyString());
        // draft 행은 dispatch 동기 실패와 동일하게 보존한다(cleanup이 정리).
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
    }

    @Test
    void createDraftTask_guardRefreshThrows_ownershipUnconfirmed_noDispatch() {
        // refresh 예외 = 소유 미확인 — 이중 dispatch 위험을 감수하지 않고 FAILED로 종결한다(보수적).
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        when(timelineTaskService.refreshDateGuard(anyLong(), any(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        String taskId = service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource());

        assertThat(taskId).isNotBlank();
        verify(timelineAiDispatcher, never()).dispatch(any());
        verify(timelineTaskService).markFailed(eq(taskId), eq(USER_ID), eq(RECORD_ID), eq(ErrorCode.ERROR_1009),
                anyString());
        verify(timelineTaskService, never()).releaseDateGuard(anyLong(), any(), anyString());
    }

    @Test
    void createDraftTask_guardReleaseFails_originalRejectionStillPropagates() {
        // 해제는 best-effort: release가 던져도 원래 거절(1003)이 그대로 전파된다(TTL이 안전망).
        DailyRecord saved = DailyRecord.createDraft(USER_ID, DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.of(saved));
        doThrow(new RuntimeException("redis down"))
                .when(timelineTaskService).releaseDateGuard(anyLong(), any(), anyString());

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1003));
    }

    @Test
    void createDraftTask_rejectsNullRecordDate() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, null, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
        // 검증은 모든 side effect 전이다 — guard 선점조차 하지 않는다.
        verify(timelineTaskService, never()).claimDateGuard(anyLong(), any(), anyString());
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
        verify(timelineTaskService, never()).claimDateGuard(anyLong(), any(), anyString());
    }

    @Test
    void createDraftTask_rejectsWindowMissingStartOrEnd() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE,
                new TimelineWindowDto(null, LocalDateTime.of(2026, 6, 18, 0, 0)), oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE,
                new TimelineWindowDto(LocalDateTime.of(2026, 6, 17, 0, 0), null), oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineTaskService, never()).claimDateGuard(anyLong(), any(), anyString());
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
        verify(timelineTaskService, never()).claimDateGuard(anyLong(), any(), anyString());
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
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1013));
        verify(timelineDraftPreparationService, never()).prepareDraft(anyLong(), any(), any(), anyString(), anyList());
        verify(timelineTaskService, never()).createProcessing(anyString(), anyLong(), anyLong(), any(), anyString(), any());
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
                windowCaptor.capture(), anyString(), any());
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
                windowCaptor.capture(), anyString(), any());
        assertThat(windowCaptor.getValue().startTime()).isEqualTo(partial.startTime());
        assertThat(windowCaptor.getValue().endTime()).isEqualTo(partial.endTime());
    }

    @Test
    void createDraftTask_recordDateIndependentOfRecordAt() {
        // recordAt이 선택 날짜(6/17)와 무관한 다음날 오후여도 recordDate는 요청값 그대로다 —
        // guard와 선생성 모두 요청 recordDate 기준으로 흐른다(소급 기록 회귀 방지).
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).thenReturn(Optional.empty());
        LocalDateTime nextDayAfternoon = LocalDateTime.of(2026, 6, 18, 15, 0);

        service.createDraftTask(VERSION, USER_ID, DATE, nextDayAfternoon, ZONE, WINDOW, oneSource());

        verify(timelineTaskService).claimDateGuard(eq(USER_ID), eq(DATE), anyString());
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
        order.verify(timelineTaskService).createProcessing(eq(taskId), eq(USER_ID), eq(RECORD_ID), any(), anyString(),
                eq(PROCESSING_STARTED_AT));
    }

    @Test
    void createDraftTask_rejectionPaths_doNotReadClock() {
        // PROCESSING에 도달하지 않는 거절(guard 선점 실패)에서는 시각을 캡처하지 않는다.
        when(timelineTaskService.claimDateGuard(eq(USER_ID), eq(DATE), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, oneSource()))
                .isInstanceOf(BusinessException.class);
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
