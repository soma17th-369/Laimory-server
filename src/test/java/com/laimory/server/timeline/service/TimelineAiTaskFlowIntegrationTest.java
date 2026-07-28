package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.dto.TimelineWindowDto;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineAiResultReceiptRepository;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 서버간 AI 작업 흐름 end-to-end 검증(실 MySQL+Redis): dispatch → 입력 조회 → 결과 저장 → 콜백.
 * 토큰은 불투명하므로 디스패처를 spy해 dispatch body의 입력 토큰을 캡처한 뒤 단계별로 chain을 따라간다.
 *
 * <p>여기서 고정하는 계약: 단계별 토큰 chain, 결과 저장 transaction(그래프+영수증+채택 source 삭제),
 * 재시도 멱등성, 실패 시 전체 rollback, 저장 없는 SUCCESS 콜백 거절.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineAiTaskFlowIntegrationTest {

    @Autowired
    private TimelineDraftTaskService draftTaskService;
    @Autowired
    private TimelineAiTaskInputService inputService;
    @Autowired
    private TimelineAiResultService resultService;
    @Autowired
    private TimelineCallbackService callbackService;
    @Autowired
    private TimelineTaskService taskService;
    @Autowired
    private TimelineDraftTaskPollingService pollingService;
    @Autowired
    private DailyRecordService dailyRecordService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineDraftSourceItemService draftSourceItemService;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private TimelineItemRepository timelineItemRepository;
    @Autowired
    private TimelineEventItemRepository timelineEventItemRepository;
    @Autowired
    private TimelineAiResultReceiptRepository receiptRepository;
    @Autowired
    private RedisGateway redis;

    @MockitoSpyBean
    private TimelineAiDispatcher dispatcher;

    private static final String VERSION = "v1";
    private static final long USER_ID = 7L;
    private static final String ZONE = "Asia/Seoul";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    // 다른 데이터와 충돌하지 않을 고정 날짜 — 클라 선택 날짜로 요청에 명시 전송한다(서버 파생 없음).
    private static final LocalDate DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDateTime RECORD_AT = LocalDateTime.of(2000, 1, 1, 12, 0);
    private static final TimelineWindowDto WINDOW = new TimelineWindowDto(
            LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2000, 1, 2, 0, 0));

    private final List<String> createdTaskIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // Item은 record FK cascade 대상이 아니므로 junction 경유로 먼저 지운다(테스트 잔존 방지).
        dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE).ifPresent(record -> {
            List<Long> eventIds = timelineEventRepository
                    .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId()).stream()
                    .map(TimelineEvent::getTimelineEventId)
                    .toList();
            List<Long> itemIds = timelineEventItemRepository.findByTimelineEventIdIn(eventIds).stream()
                    .map(TimelineEventItem::getTimelineItemId)
                    .distinct()
                    .toList();
            // 영수증은 record FK cascade로 함께 지워진다(events/junction도 동일).
            dailyRecordRepository.deleteById(record.getDailyRecordId());
            timelineItemRepository.deleteAllByIdInBatch(itemIds);
        });
        createdTaskIds.forEach(id -> {
            draftSourceItemService.deleteByTaskId(id);
            receiptRepository.findById(id).ifPresent(receiptRepository::delete);
            // redis는 RedisGateway → 환경 prefix는 내부에서 자동 부착(논리 키만 넘김).
            redis.delete("timeline:draft-task:" + id);
        });
        createdTaskIds.clear();
    }

    @Test
    void fullChain_inputThenResultThenCallback_storesGraphAndSucceeds() {
        String taskId = createDraft(sources());
        DailyRecord record = dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE).orElseThrow();

        // dispatch body에는 taskId와 입력 토큰만 실린다(DB 식별자·window 없음).
        AiTimelineDispatchRequest dispatched = capturedRequest();
        assertThat(dispatched.taskId()).isEqualTo(taskId);
        String inputToken = dispatched.taskToken();

        // Redis에는 원문 토큰이 없고 세 단계 hash만 보관된다.
        String rawJson = redis.get("timeline:draft-task:" + taskId);
        assertThat(rawJson).doesNotContain(inputToken);
        TimelineDraftTask stored = taskService.find(taskId).orElseThrow();
        assertThat(stored.matchesInputToken(inputToken)).isTrue();
        assertThat(stored.userId()).isEqualTo(USER_ID);
        assertThat(stored.dailyRecordId()).isEqualTo(record.getDailyRecordId());

        // 1단계: 입력 조회 — DB 식별자 없이 정규 입력과 다음 토큰이 나온다.
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, inputToken);
        assertThat(input.taskId()).isEqualTo(taskId);
        assertThat(input.recordDate()).isEqualTo(DATE);
        assertThat(input.recordTimeZone()).isEqualTo(ZONE);
        assertThat(input.window().startAt()).isEqualTo(OffsetDateTime.of(WINDOW.startTime(), KST));
        assertThat(input.window().endAt()).isEqualTo(OffsetDateTime.of(WINDOW.endTime(), KST));
        assertThat(input.sourceItems()).singleElement().satisfies(item -> {
            assertThat(item.rawId()).isEqualTo("raw-p1");
            assertThat(item.itemType()).isEqualTo(ItemType.PHOTO);
            // staging의 wall-clock에 record timezone offset이 붙어 나간다.
            assertThat(item.startAt()).isEqualTo(OffsetDateTime.of(DATE.atTime(9, 0), KST));
            assertThat(item.payload().get("filename").asText()).isNotBlank();
        });
        // 같은 입력 토큰으로 재조회해도 같은 다음 토큰이 나온다(응답 유실 후 재시도 안전).
        assertThat(inputService.getInput(VERSION, taskId, inputToken).resultToken())
                .isEqualTo(input.resultToken());

        // 2단계: 결과 저장 — Event/Item/junction 저장 + 채택 source 삭제 + 영수증이 한 transaction이다.
        String callbackToken = resultService
                .storeResult(VERSION, taskId, input.resultToken(), resultFrom(input)).callbackToken();
        assertThat(callbackToken).isEqualTo(TaskTokens.deriveCallbackToken(input.resultToken(), taskId));

        List<TimelineEvent> events = timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId());
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getTitle()).isEqualTo("아침");
            assertThat(event.getEventType()).isEqualTo(TimelineEventType.UNKNOWN);
            // offset 입력이 record timezone wall-clock으로 정규화돼 저장된다.
            assertThat(event.getStartAt()).isEqualTo(DATE.atTime(9, 0));
        });
        assertThat(timelineEventItemRepository.findByTimelineEventId(events.get(0).getTimelineEventId())).hasSize(1);
        assertThat(draftSourceItemService.findByTaskId(taskId)).isEmpty();
        assertThat(receiptRepository.existsById(taskId)).isTrue();

        // 3단계: 콜백 — 상태 전이만 한다.
        callbackService.handleCallback(VERSION, taskId, callbackToken, success());

        DraftTaskStatusResponse status = pollingService.poll(VERSION, USER_ID, taskId);
        assertThat(status.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(status.result().events()).hasSize(1);
        assertThat(status.result().events().get(0).items()).hasSize(1);

        // 같은 결과의 콜백 재전송은 그대로 성공한다(재시도 안전 — 상태는 그대로 SUCCESS).
        callbackService.handleCallback(VERSION, taskId, callbackToken, success());
        assertThat(pollingService.poll(VERSION, USER_ID, taskId).status()).isEqualTo(TaskStatus.SUCCESS);
    }

    @Test
    void resultRetry_afterLostResponse_doesNotDuplicateGraph() {
        String taskId = createDraft(sources());
        DailyRecord record = dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE).orElseThrow();
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, capturedRequest().taskToken());

        String first = resultService.storeResult(VERSION, taskId, input.resultToken(), resultFrom(input))
                .callbackToken();
        // 응답 유실 시나리오: AI가 같은 결과를 다시 보낸다.
        String second = resultService.storeResult(VERSION, taskId, input.resultToken(), resultFrom(input))
                .callbackToken();

        assertThat(second).isEqualTo(first);
        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId())).hasSize(1);
        assertThat(timelineItemRepository.findAll())
                .filteredOn(item -> "raw-p1".equals(item.getRawId()))
                .hasSize(1);
    }

    @Test
    void resultWithUnknownRawId_rollsBackEverything() {
        String taskId = createDraft(sources());
        DailyRecord record = dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE).orElseThrow();
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, capturedRequest().taskToken());
        AiTimelineResultRequest invalid = new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.UNKNOWN, "아침", null,
                OffsetDateTime.of(DATE.atTime(9, 0), KST), null, List.of("raw-not-mine"))));

        assertThatThrownBy(() -> resultService.storeResult(VERSION, taskId, input.resultToken(), invalid))
                .isInstanceOf(IllegalArgumentException.class);

        // 영수증까지 함께 롤백된다 — 부분 반영이 남지 않고 올바른 결과의 재시도가 막히지 않는다.
        assertThat(receiptRepository.existsById(taskId)).isFalse();
        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId())).isEmpty();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();

        // 같은 task로 올바른 결과를 다시 보내면 정상 저장된다.
        resultService.storeResult(VERSION, taskId, input.resultToken(), resultFrom(input));
        assertThat(receiptRepository.existsById(taskId)).isTrue();
    }

    @Test
    void successCallbackWithoutStoredResult_isRejected1017() {
        String taskId = createDraft(sources());
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, capturedRequest().taskToken());
        String callbackToken = TaskTokens.deriveCallbackToken(input.resultToken(), taskId);

        assertThatThrownBy(() -> callbackService.handleCallback(VERSION, taskId, callbackToken, success()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        assertThat(taskService.find(taskId).orElseThrow().status()).isEqualTo(TaskStatus.PROCESSING);
    }

    @Test
    void wrongInputToken_rejected401_recordAndSourcesPreserved() {
        String taskId = createDraft(sources());

        assertThatThrownBy(() -> inputService.getInput(VERSION, taskId, "wrong-token"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));

        assertThat(taskService.find(taskId).orElseThrow().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).isPresent();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();
    }

    @Test
    void failedCallbackWithResultStageToken_keepsEmptyDraftAndSources() {
        String taskId = createDraft(sources());
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, capturedRequest().taskToken());

        // 실패는 결과 저장 단계를 거치지 않으므로 결과 저장 단계 토큰으로 보고한다.
        callbackService.handleCallback(VERSION, taskId, input.resultToken(),
                new DraftTaskCallbackRequest(TaskStatus.FAILED, -1008, "inference failed"));

        DraftTaskStatusResponse status = pollingService.poll(VERSION, USER_ID, taskId);
        assertThat(status.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(status.error()).isEqualTo(-1008);
        // empty DRAFT는 삭제하지 않는다 — 같은 날짜 재시도가 재사용한다. source는 retention cleanup 대상.
        assertThat(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).isPresent();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();
        assertThat(receiptRepository.existsById(taskId)).isFalse();

        // 같은 FAILED 재전송은 성공, 상충 결과(SUCCESS)는 -1017이다.
        callbackService.handleCallback(VERSION, taskId, input.resultToken(),
                new DraftTaskCallbackRequest(TaskStatus.FAILED, -1008, "retry"));
        String callbackToken = TaskTokens.deriveCallbackToken(input.resultToken(), taskId);
        assertThatThrownBy(() -> callbackService.handleCallback(VERSION, taskId, callbackToken, success()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1017));
        assertThat(taskService.find(taskId).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void stepsAfterTaskExpiry_return404_withoutResurrection() {
        // task 만료(=key 소멸) 뒤 도착한 유효-토큰 요청은 404(-1001)로 끝난다(부활 금지).
        // 만료는 실제 TTL 대기 대신 key 삭제로 재현한다(의미 동일).
        String taskId = createDraft(sources());
        String inputToken = capturedRequest().taskToken();

        redis.delete("timeline:draft-task:" + taskId);

        assertThatThrownBy(() -> inputService.getInput(VERSION, taskId, inputToken))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1001));
        assertThat(taskService.find(taskId)).isEmpty();

        assertThatThrownBy(() -> pollingService.poll(VERSION, USER_ID, taskId))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1001));
    }

    private String createDraft(List<SourceItemDto> sources) {
        String taskId = draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources);
        createdTaskIds.add(taskId);
        return taskId;
    }

    /** 조회한 입력 전부를 Event 하나로 묶는 최소 결과(실 AI 추론 대행). */
    private AiTimelineResultRequest resultFrom(AiTimelineTaskInputResponse input) {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.UNKNOWN, "아침", null,
                input.sourceItems().get(0).startAt(), null,
                input.sourceItems().stream().map(AiTimelineTaskInputResponse.SourceItem::rawId).toList())));
    }

    private AiTimelineDispatchRequest capturedRequest() {
        ArgumentCaptor<AiTimelineDispatchRequest> captor = ArgumentCaptor.forClass(AiTimelineDispatchRequest.class);
        verify(dispatcher).dispatch(captor.capture());
        return captor.getValue();
    }

    private DraftTaskCallbackRequest success() {
        return new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null, null);
    }

    private List<SourceItemDto> sources() {
        return List.of(new SourceItemDto(ItemType.PHOTO, "raw-p1", DATE.atTime(9, 0), null,
                new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://p", 37.5, 127.0, null, null)));
    }
}
