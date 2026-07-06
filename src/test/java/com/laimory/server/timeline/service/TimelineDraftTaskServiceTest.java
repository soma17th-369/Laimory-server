package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.HealthMetric;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.HealthPayload;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.MovementEndpoint;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.NotificationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import org.springframework.test.util.ReflectionTestUtils;

/** POST 오케스트레이터 단위 검증. recordDate 도출·SAVED 거절·draft 저장·보상 삭제·디스패치 합성. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class TimelineDraftTaskServiceTest {

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineTaskService timelineTaskService;
    @Mock
    private TimelineDraftSourceItemService timelineDraftSourceItemService;
    @Mock
    private SourceItemGeoEnrichmentService sourceItemGeoEnrichmentService;
    @Mock
    private TimelineEventSuggestionDispatcher timelineEventSuggestionDispatcher;

    private TimelineDraftTaskService service;

    private static final String VERSION = "v1";
    private static final String ZONE = "Asia/Seoul";
    // 벽시계 정오(12:00) → 정오 경계상 당일(6/17).
    private static final LocalDateTime RECORD_AT = LocalDateTime.of(2026, 6, 17, 12, 0);
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    // 엄격 검증을 통과하는 유효 filename(UUIDv7 + 허용 ext).
    private static final String VALID_FILENAME = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";

    @BeforeEach
    void setUp() {
        service = new TimelineDraftTaskService(
                dailyRecordService, timelineTaskService, timelineDraftSourceItemService,
                sourceItemGeoEnrichmentService, timelineEventSuggestionDispatcher, new ObjectMapper());
        // 기본 스텁: enrich pass-through(재구성 자체는 SourceItemGeoEnrichmentServiceTest가 검증).
        // 검증 실패 테스트는 enrich까지 도달하지 않으므로 lenient.
        lenient().when(sourceItemGeoEnrichmentService.enrich(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private List<SourceItemDto> oneSource() {
        return List.of(new SourceItemDto(ItemType.PHOTO, LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new PhotoPayload(VALID_FILENAME, "content://x", 1.0, 2.0, null)));
    }

    @Test
    void createDraftTask_happyPath_savesDraftsThenProcessingThenDispatches() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, RECORD_AT, ZONE, oneSource());

        assertThat(taskId).isNotBlank();
        // recordDate가 recordAt+zone에서 정오 경계로 도출돼 createProcessing에 전달된다.
        verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), eq(RECORD_AT), eq(ZONE), anyString());
        // dispatch는 2-arg(taskId, token) — sourceItems·callbackUrl 없음.
        verify(timelineEventSuggestionDispatcher).dispatch(eq(taskId), anyString());

        // 순서 불변식: 지오코딩 enrich(저장 전 — AI가 DB에서 직접 읽음) → draft 저장 → Redis PROCESSING → dispatch.
        InOrder order = inOrder(sourceItemGeoEnrichmentService, timelineDraftSourceItemService,
                timelineTaskService, timelineEventSuggestionDispatcher);
        order.verify(sourceItemGeoEnrichmentService).enrich(anyList());
        order.verify(timelineDraftSourceItemService).saveAll(anyList());
        order.verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), eq(RECORD_AT), eq(ZONE), anyString());
        order.verify(timelineEventSuggestionDispatcher).dispatch(eq(taskId), anyString());
    }

    @Test
    void createDraftTask_savesDraftRowsBuiltFromSources() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, RECORD_AT, ZONE, oneSource());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineDraftSourceItem>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineDraftSourceItemService).saveAll(rowsCaptor.capture());
        List<TimelineDraftSourceItem> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(1);
        TimelineDraftSourceItem row = rows.get(0);
        assertThat(row.getTaskId()).isEqualTo(taskId);
        assertThat(row.getUserId()).isEqualTo(0L);
        assertThat(row.getItemType()).isEqualTo(ItemType.PHOTO);
        assertThat(row.getStartAt()).isEqualTo(LocalDateTime.of(2026, 6, 17, 9, 0));
        // payload는 discriminator 없는 raw JsonNode.
        assertThat(row.getPayload().get("filename").asText()).isEqualTo(VALID_FILENAME);
        assertThat(row.getPayload().get("clientPhotoUri").asText()).isEqualTo("content://x");
        assertThat(row.getPayload().has("itemType")).isFalse();
    }

    @Test
    void createDraftTask_storesOnlyTokenHash_notRawToken() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());

        String taskId = service.createDraftTask(VERSION, RECORD_AT, ZONE, oneSource());

        // Redis에 저장되는 값(createProcessing 인자)은 해시, AI에 전달되는 값(dispatch 인자)은 원문이어야 한다.
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), eq(RECORD_AT), eq(ZONE), hashCaptor.capture());
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(timelineEventSuggestionDispatcher).dispatch(eq(taskId), tokenCaptor.capture());

        String storedHash = hashCaptor.getValue();
        String dispatchedToken = tokenCaptor.getValue();
        assertThat(dispatchedToken).isNotBlank();
        assertThat(storedHash).isNotEqualTo(dispatchedToken);
        assertThat(storedHash).isEqualTo(CallbackTokens.hash(dispatchedToken));
    }

    @Test
    void createDraftTask_reusesDraftRecord_doesNotReject() {
        DailyRecord draft = DailyRecord.createDraft(0L, DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(draft, "dailyRecordId", 3L);
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.of(draft));

        String taskId = service.createDraftTask(VERSION, RECORD_AT, ZONE, oneSource());

        assertThat(taskId).isNotBlank();
        verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), eq(RECORD_AT), eq(ZONE), anyString());
    }

    @Test
    void createDraftTask_rejectsSavedRecord() {
        DailyRecord saved = DailyRecord.createDraft(0L, DATE, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(saved, "dailyRecordId", 5L);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, oneSource()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1003));
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
        verify(timelineTaskService, never()).createProcessing(anyString(), any(), any(), any(), anyString());
        verify(timelineEventSuggestionDispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void createDraftTask_whenRedisFails_compensatesByDeletingDraftsAndRethrows() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("redis down"))
                .when(timelineTaskService).createProcessing(anyString(), any(), any(), any(), anyString());

        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, oneSource()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("redis down");

        // 보상 삭제: 방금 저장한 draft 행을 taskId로 지운다(고아 draft 방지). dispatch는 호출되지 않는다.
        verify(timelineDraftSourceItemService).deleteByTaskId(anyString());
        verify(timelineEventSuggestionDispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void createDraftTask_whenDispatchThrows_marksFailedKeepsDraftsAndReturnsTaskId() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("boom"))
                .when(timelineEventSuggestionDispatcher).dispatch(anyString(), anyString());

        // dispatch가 동기 예외를 던져도 taskId는 반환되고 task는 FAILED로 고정된다(고아 PROCESSING 방지).
        String taskId = service.createDraftTask(VERSION, RECORD_AT, ZONE, oneSource());

        assertThat(taskId).isNotBlank();
        verify(timelineTaskService).createProcessing(eq(taskId), eq(DATE), eq(RECORD_AT), eq(ZONE), anyString());
        // raw 메시지("boom")는 저장하지 않는다 — 분류 코드만(상세는 로그로).
        verify(timelineTaskService).markFailed(eq(taskId), eq(DATE), eq(ErrorCode.ERROR_1009), anyString());
        // dispatch 실패는 draft를 보존한다(cleanup이 정리). 보상 삭제 없음.
        verify(timelineDraftSourceItemService, never()).deleteByTaskId(anyString());
    }

    @Test
    void createDraftTask_rejectsNullRecordAt() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, null, ZONE, oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullRecordTimeZone() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, null, oneSource()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsEmptySourceItems() {
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullItemType() {
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(null, null, null, new PhotoPayload("u", "content://x", 1.0, 2.0, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsNullPayload() {
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.PHOTO, null, null, null));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDraftTask_rejectsInvalidPhotoFilename() {
        // PHOTO filename이 UUIDv7+허용ext 패턴이 아니면 입력 경계에서 400으로 막는다(저장 전).
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.PHOTO, LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new PhotoPayload("../etc/passwd", "content://x", 1.0, 2.0, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_rejectsMissingClientPhotoUri() {
        // clientPhotoUri는 1차 로컬 캐싱용이라 PHOTO엔 필수다(누락/blank → 400, 저장 전).
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.PHOTO, LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new PhotoPayload(VALID_FILENAME, null, 1.0, 2.0, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_rejectsHealthMissingMetricOrMeasuredValue() {
        // HEALTH는 metric 필수 + 지표별 값 필드(SLEEP=durationMinutes, 그 외=value) 필수(누락 → 400, 저장 전).
        List<SourceItemDto> missingMetric = List.of(new SourceItemDto(
                ItemType.HEALTH, LocalDateTime.of(2026, 6, 17, 0, 0), null,
                new HealthPayload(null, 10145.0, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, missingMetric))
                .isInstanceOf(IllegalArgumentException.class);

        List<SourceItemDto> missingValue = List.of(new SourceItemDto(
                ItemType.HEALTH, LocalDateTime.of(2026, 6, 17, 0, 0), null,
                new HealthPayload(HealthMetric.STEPS, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, missingValue))
                .isInstanceOf(IllegalArgumentException.class);

        // SLEEP은 value가 아니라 durationMinutes를 요구한다 — value만 실려 오면 400.
        List<SourceItemDto> sleepWithValueOnly = List.of(new SourceItemDto(
                ItemType.HEALTH, LocalDateTime.of(2026, 6, 17, 4, 0), null,
                new HealthPayload(HealthMetric.SLEEP, 210.0, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sleepWithValueOnly))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_rejectsHealthValueFieldNotMatchingMetric() {
        // 지표와 안 맞는 반대 필드가 같이 실려 오면 모순 입력 — "반대 필드는 키 생략" 저장 계약을 검증이 보장한다.
        List<SourceItemDto> sleepWithBoth = List.of(new SourceItemDto(
                ItemType.HEALTH, LocalDateTime.of(2026, 6, 17, 4, 0), null,
                new HealthPayload(HealthMetric.SLEEP, 99999.0, 210.0)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sleepWithBoth))
                .isInstanceOf(IllegalArgumentException.class);

        List<SourceItemDto> stepsWithDuration = List.of(new SourceItemDto(
                ItemType.HEALTH, LocalDateTime.of(2026, 6, 17, 0, 0), null,
                new HealthPayload(HealthMetric.STEPS, 10145.0, 210.0)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, stepsWithDuration))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_rejectsNegativeHealthValue() {
        // 값은 보/미터/분이라 음수가 무의미 — 입력 경계에서 400으로 막는다(저장 전).
        List<SourceItemDto> negativeValue = List.of(new SourceItemDto(
                ItemType.HEALTH, LocalDateTime.of(2026, 6, 17, 0, 0), null,
                new HealthPayload(HealthMetric.STEPS, -1.0, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, negativeValue))
                .isInstanceOf(IllegalArgumentException.class);

        List<SourceItemDto> negativeDuration = List.of(new SourceItemDto(
                ItemType.HEALTH, LocalDateTime.of(2026, 6, 17, 4, 0), null,
                new HealthPayload(HealthMetric.SLEEP, null, -1.0)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, negativeDuration))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_rejectsNotificationWithoutTitleAndText() {
        // title/text 둘 다 blank면 NON_NULL 직렬화로 빈 payload가 저장되므로 입력 경계에서 400으로 막는다.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.NOTIFICATION, LocalDateTime.of(2026, 6, 17, 21, 12), null,
                new NotificationPayload("카카오톡", null, " ")));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_rejectsLocationMissingCoordinate() {
        // LOCATION 좌표는 필수(지오코딩 enrich 전제) — 누락 → 400, 저장 전.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.LOCATION, LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new LocationPayload(null, 127.0557, null, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_rejectsNonFiniteCoordinate() {
        // NaN은 범위 비교(-90~90)를 전부 통과하므로 isFinite 검증이 별도로 막아야 한다.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.LOCATION, LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new LocationPayload(Double.NaN, 127.0557, null, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_rejectsOutOfRangeCoordinate() {
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.LOCATION, LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new LocationPayload(37.5445, 180.5, null, null, null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_rejectsMovementMissingEndpoint() {
        // MOVEMENT는 start/end 객체(각 좌표 포함)가 필수다.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.MOVEMENT, LocalDateTime.of(2026, 6, 17, 8, 30), null,
                new MovementPayload(null, endpoint(37.5445, 127.0557), "IN_VEHICLE", null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_rejectsNegativeDistanceMeters() {
        // 이동 거리는 음수가 무의미(HEALTH value 음수 거절과 같은 입력 경계 정책).
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.MOVEMENT, LocalDateTime.of(2026, 6, 17, 8, 30), null,
                new MovementPayload(endpoint(37.4979, 127.0276), endpoint(37.5445, 127.0557),
                        "IN_VEHICLE", -1.0)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_rejectsMismatchedItemTypeAndPayload() {
        // HTTP 경로는 Jackson 디스크리미네이터가 일치를 보장하지만, 프로그래밍 방식 생성 경로를 방어한다.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.LOCATION, LocalDateTime.of(2026, 6, 17, 8, 30), null,
                new MovementPayload(endpoint(37.4979, 127.0276), endpoint(37.5445, 127.0557),
                        "IN_VEHICLE", null)));
        assertThatThrownBy(() -> service.createDraftTask(VERSION, RECORD_AT, ZONE, sources))
                .isInstanceOf(IllegalArgumentException.class);
        verify(timelineDraftSourceItemService, never()).saveAll(anyList());
    }

    @Test
    void createDraftTask_savesRowsBuiltFromEnrichedItems_notRawInput() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());
        // enrich(재구성) 결과가 저장본이다 — 원본이 아니라 반환 리스트로 row를 빌드해야 한다.
        List<SourceItemDto> sources = List.of(new SourceItemDto(
                ItemType.LOCATION, LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new LocationPayload(37.5340, 126.9668, null, null, null)));
        List<SourceItemDto> enriched = List.of(new SourceItemDto(
                ItemType.LOCATION, LocalDateTime.of(2026, 6, 17, 9, 0), null,
                new LocationPayload(37.5340, 126.9668,
                        "서울 용산구 청파로20길 95", List.of("서울드래곤시티", "그랑씨엘"), "1시간45분")));
        when(sourceItemGeoEnrichmentService.enrich(sources)).thenReturn(enriched);

        service.createDraftTask(VERSION, RECORD_AT, ZONE, sources);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineDraftSourceItem>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineDraftSourceItemService).saveAll(rowsCaptor.capture());
        assertThat(rowsCaptor.getValue().get(0).getPayload().get("address").asText())
                .isEqualTo("서울 용산구 청파로20길 95");
        assertThat(rowsCaptor.getValue().get(0).getPayload().get("places").size()).isEqualTo(2);
        assertThat(rowsCaptor.getValue().get(0).getPayload().get("durationText").asText()).isEqualTo("1시간45분");
    }

    private static MovementEndpoint endpoint(double latitude, double longitude) {
        return new MovementEndpoint(latitude, longitude, null, null);
    }

    @Test
    void createDraftTask_savesHealthAndNotificationRows_omittingNullPayloadFields() {
        when(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).thenReturn(Optional.empty());
        List<SourceItemDto> sources = List.of(
                new SourceItemDto(ItemType.HEALTH, LocalDateTime.of(2026, 6, 17, 0, 0),
                        LocalDateTime.of(2026, 6, 18, 0, 0), new HealthPayload(HealthMetric.STEPS, 10145.0, null)),
                new SourceItemDto(ItemType.HEALTH, LocalDateTime.of(2026, 6, 17, 4, 0),
                        LocalDateTime.of(2026, 6, 17, 7, 30), new HealthPayload(HealthMetric.SLEEP, null, 210.0)),
                new SourceItemDto(ItemType.NOTIFICATION, LocalDateTime.of(2026, 6, 17, 21, 12), null,
                        new NotificationPayload(null, "제목", null)));

        service.createDraftTask(VERSION, RECORD_AT, ZONE, sources);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimelineDraftSourceItem>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(timelineDraftSourceItemService).saveAll(rowsCaptor.capture());
        List<TimelineDraftSourceItem> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getItemType()).isEqualTo(ItemType.HEALTH);
        assertThat(rows.get(0).getPayload().get("metric").asText()).isEqualTo("STEPS");
        assertThat(rows.get(0).getPayload().get("value").asDouble()).isEqualTo(10145.0);
        assertThat(rows.get(0).getPayload().has("durationMinutes")).isFalse();
        // SLEEP은 value 대신 durationMinutes(분)로 저장된다(AI input 규격).
        assertThat(rows.get(1).getPayload().get("metric").asText()).isEqualTo("SLEEP");
        assertThat(rows.get(1).getPayload().get("durationMinutes").asDouble()).isEqualTo(210.0);
        assertThat(rows.get(1).getPayload().has("value")).isFalse();
        assertThat(rows.get(2).getItemType()).isEqualTo(ItemType.NOTIFICATION);
        assertThat(rows.get(2).getPayload().get("title").asText()).isEqualTo("제목");
        // NON_NULL: null 필드(appName/text)는 저장 JSON에서 생략된다.
        assertThat(rows.get(2).getPayload().has("appName")).isFalse();
        assertThat(rows.get(2).getPayload().has("text")).isFalse();
    }
}
