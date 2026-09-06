package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineResultResponse;
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
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 서버간 AI 작업 흐름 end-to-end 검증(실 MySQL+Redis): dispatch → 입력 조회 → 결과 저장 → 콜백.
 * 토큰은 불투명하므로 디스패처를 spy해 dispatch body의 최초 task token을 캡처한다.
 *
 * <p>여기서 고정하는 계약: 단계별 task token 회전, Redis process stage, 결과 저장 transaction,
 * 실패 시 graph/token rollback, 저장 전 SUCCESS 콜백 거절.
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
    private RedisGateway redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private TimelineAiDispatcher dispatcher;

    private static final String VERSION = "v1";
    private static final UUID SUBJECT_ID = id(7L);
    private static final String ZONE = "Asia/Seoul";
    private static final String QUESTION = "그날 아침 기분은 어땠나요?";
    private static final String PLACE = "성수 카페";
    private static final String ADDRESS = "서울특별시 성동구 아차산로 17";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    // 다른 데이터와 충돌하지 않을 고정 날짜 — 클라 선택 날짜로 요청에 명시 전송한다(서버 파생 없음).
    private static final LocalDate DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDateTime RECORD_AT = LocalDateTime.of(2000, 1, 1, 12, 0);
    private static final TimelineWindowDto WINDOW = new TimelineWindowDto(
            LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2000, 1, 2, 0, 0));
    // draft 입력 경계의 canonical lowercase UUID 검증(version 무관)을 통과하는 rawId.
    private static final String RAW_ID = "0190a1b2-0001-7000-8000-000000000001";

    private final List<String> createdTaskIds = new ArrayList<>();

    @BeforeEach
    void setUpSubject() {
        ensureExists(jdbcTemplate, SUBJECT_ID);
    }

    @AfterEach
    void cleanUp() {
        // Item은 record FK cascade 대상이 아니므로 junction 경유로 먼저 지운다(테스트 잔존 방지).
        dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, DATE).ifPresent(record -> {
            List<Long> eventIds = timelineEventRepository
                    .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId()).stream()
                    .map(TimelineEvent::getTimelineEventId)
                    .toList();
            List<Long> itemIds = timelineEventItemRepository.findByTimelineEventIdIn(eventIds).stream()
                    .map(TimelineEventItem::getTimelineItemId)
                    .distinct()
                    .toList();
            dailyRecordRepository.deleteById(record.getDailyRecordId());
            timelineItemRepository.deleteAllByIdInBatch(itemIds);
        });
        createdTaskIds.forEach(id -> {
            draftSourceItemService.deleteByTaskId(id);
            // redis는 RedisGateway → 환경 prefix는 내부에서 자동 부착(논리 키만 넘김).
            redis.delete("timeline:draft-task:" + id);
        });
        createdTaskIds.clear();
        // 완료 푸시 경로가 마스터 행이 없으면 기본 행을 보정하므로(#314) mapping보다 먼저 지운다(FK RESTRICT).
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, SUBJECT_ID);
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", SUBJECT_ID.toString());
    }

    @Test
    void fullChain_inputThenResultThenCallback_storesGraphAndSucceeds() {
        String taskId = createDraft(sources());
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, DATE).orElseThrow();

        // AI dispatch는 taskToken 명칭을 쓰고 dailyRecordId·window를 유지한다.
        AiTimelineDispatchRequest dispatched = capturedRequest();
        assertThat(dispatched.taskId()).isEqualTo(taskId);
        assertThat(dispatched.dailyRecordId()).isEqualTo(record.getDailyRecordId());
        assertThat(dispatched.window().startAt()).isEqualTo(OffsetDateTime.of(WINDOW.startTime(), KST));
        assertThat(dispatched.window().endAt()).isEqualTo(OffsetDateTime.of(WINDOW.endTime(), KST));
        String inputToken = dispatched.taskToken();

        // Redis에는 원문 토큰 없이 단일 hash와 내부 stage만 보관된다.
        String rawJson = redis.get("timeline:draft-task:" + taskId);
        assertThat(rawJson).doesNotContain(inputToken);
        TimelineDraftTask stored = taskService.find(taskId).orElseThrow();
        assertThat(stored.matchesToken(inputToken)).isTrue();
        assertThat(stored.stage()).isEqualTo(ProcessStage.INPUT_PENDING);
        assertThat(stored.subjectId()).isEqualTo(SUBJECT_ID);
        assertThat(stored.dailyRecordId()).isEqualTo(record.getDailyRecordId());

        // 1단계: 입력 조회 — DB 식별자 없이 정규 입력이 나오고 RESULT_PENDING으로 전이한다.
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, inputToken);
        String resultToken = input.taskToken();
        assertThat(input.taskId()).isEqualTo(taskId);
        assertThat(input.recordDate()).isEqualTo(DATE);
        assertThat(input.recordTimeZone()).isEqualTo(ZONE);
        assertThat(input.window().startAt()).isEqualTo(OffsetDateTime.of(WINDOW.startTime(), KST));
        assertThat(input.window().endAt()).isEqualTo(OffsetDateTime.of(WINDOW.endTime(), KST));
        assertThat(input.sourceItems()).singleElement().satisfies(item -> {
            assertThat(item.rawId()).isEqualTo(RAW_ID);
            assertThat(item.itemType()).isEqualTo(ItemType.PHOTO);
            // staging의 wall-clock에 record timezone offset이 붙어 나간다.
            assertThat(item.startAt()).isEqualTo(OffsetDateTime.of(DATE.atTime(9, 0), KST));
            assertThat(item.payload().get("filename").asText()).isNotBlank();
        });
        assertThat(resultToken).isNotEqualTo(inputToken);
        assertThat(taskService.find(taskId).orElseThrow().matchesToken(resultToken)).isTrue();
        assertThat(taskService.find(taskId).orElseThrow().stage()).isEqualTo(ProcessStage.RESULT_PENDING);

        // 2단계: input 응답 token으로 결과 저장 — graph transaction 뒤 callback token을 받는다.
        AiTimelineResultResponse result =
                resultService.storeResult(VERSION, taskId, resultToken, resultFrom(input));
        String callbackToken = result.taskToken();
        assertThat(callbackToken).isNotEqualTo(resultToken);
        assertThat(taskService.find(taskId).orElseThrow().stage()).isEqualTo(ProcessStage.CALLBACK_PENDING);
        assertThat(taskService.find(taskId).orElseThrow().matchesToken(callbackToken)).isTrue();

        List<TimelineEvent> events = timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId());
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getTitle()).isEqualTo("아침");
            assertThat(event.getEventType()).isEqualTo(TimelineEventType.UNKNOWN);
            assertThat(event.getQuestion()).isEqualTo(QUESTION);
            assertThat(event.getPlace()).isEqualTo(PLACE);
            assertThat(event.getAddress()).isEqualTo(ADDRESS);
            // offset 입력이 record timezone wall-clock으로 정규화돼 저장된다.
            assertThat(event.getStartAt()).isEqualTo(DATE.atTime(9, 0));
        });
        assertThat(timelineEventItemRepository.findByTimelineEventId(events.get(0).getTimelineEventId())).hasSize(1);
        assertThat(draftSourceItemService.findByTaskId(taskId)).isEmpty();

        // 3단계: 콜백 — 상태 전이만 한다.
        callbackService.handleCallback(VERSION, taskId, callbackToken, success());

        DraftTaskStatusResponse status = pollingService.poll(VERSION, SUBJECT_ID, taskId);
        assertThat(status.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(status.result().events()).hasSize(1);
        assertThat(status.result().events().get(0).items()).hasSize(1);
        assertThat(status.result().events().get(0).question()).isEqualTo(QUESTION);
        assertThat(status.result().events().get(0).place()).isEqualTo(PLACE);
        assertThat(status.result().events().get(0).address()).isEqualTo(ADDRESS);

        // 같은 결과의 콜백 재전송은 그대로 성공한다(재시도 안전 — 상태는 그대로 SUCCESS).
        callbackService.handleCallback(VERSION, taskId, callbackToken, success());
        assertThat(pollingService.poll(VERSION, SUBJECT_ID, taskId).status()).isEqualTo(TaskStatus.SUCCESS);
    }

    /** question 없는 기존 형식과 공백 문자열은 모두 "질문 없음"(NULL)으로 저장된다. */
    @Test
    void result_blankQuestion_isStoredAsNull() {
        String taskId = createDraft(sources());
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, DATE).orElseThrow();
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, capturedRequest().taskToken());

        resultService.storeResult(VERSION, taskId, input.taskToken(), resultFrom(input, "   "));

        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId()))
                .singleElement()
                .satisfies(event -> assertThat(event.getQuestion()).isNull());
    }

    /** place·address도 같은 규칙이다 — 필드 없는 형식과 공백 문자열 모두 NULL로 저장된다. */
    @Test
    void result_blankPlaceAndAddress_areStoredAsNull() {
        String taskId = createDraft(sources());
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, DATE).orElseThrow();
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, capturedRequest().taskToken());

        resultService.storeResult(VERSION, taskId, input.taskToken(), resultFrom(input, QUESTION, "  ", null));

        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getPlace()).isNull();
                    assertThat(event.getAddress()).isNull();
                });
    }

    @Test
    void resultRetry_afterLostResponse_reissuesCallbackTokenWithoutDuplicatingGraph() {
        // 200 응답이 유실된 상황 — AI는 이미 소비한 result token으로 같은 body를 다시 보낸다.
        String taskId = createDraft(sources());
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, DATE).orElseThrow();
        String taskToken = capturedRequest().taskToken();
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, taskToken);
        String resultToken = input.taskToken();

        AiTimelineResultResponse first = resultService.storeResult(VERSION, taskId, resultToken, resultFrom(input));

        AiTimelineResultResponse replay = resultService.storeResult(VERSION, taskId, resultToken, resultFrom(input));

        // 신규 저장과 재시도의 응답 shape는 같고, token만 매번 새로 발급된다.
        assertThat(replay.taskToken()).isNotEqualTo(first.taskToken()).isNotEqualTo(resultToken);
        // receipt를 늘려도 Redis에는 여전히 hash만 남는다.
        String rawJson = redis.get("timeline:draft-task:" + taskId);
        assertThat(rawJson).contains("retryReceipt")
                .doesNotContain(resultToken).doesNotContain(first.taskToken()).doesNotContain(replay.taskToken());

        // Event·Item·junction 모두 한 번만 삽입되고 채택 source 삭제도 한 번만 반영된다.
        List<TimelineEvent> events = timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId());
        assertThat(events).hasSize(1);
        assertThat(timelineItemRepository.findAll())
                .filteredOn(item -> RAW_ID.equals(item.getRawId()))
                .hasSize(1);
        assertThat(timelineEventItemRepository.findByTimelineEventId(events.getFirst().getTimelineEventId()))
                .hasSize(1);
        assertThat(draftSourceItemService.findByTaskId(taskId)).isEmpty();
        assertThat(taskService.find(taskId).orElseThrow().stage()).isEqualTo(ProcessStage.CALLBACK_PENDING);
    }

    @Test
    void resultRetry_reissuedToken_completesSuccessCallbackChain() {
        String taskId = createDraft(sources());
        String taskToken = capturedRequest().taskToken();
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, taskToken);
        String resultToken = input.taskToken();

        String deadToken = resultService.storeResult(VERSION, taskId, resultToken, resultFrom(input)).taskToken();
        String liveToken = resultService.storeResult(VERSION, taskId, resultToken, resultFrom(input)).taskToken();

        // 재발급 성공으로 직전 callback token은 즉시 무효가 된다.
        assertThatThrownBy(() -> callbackService.handleCallback(VERSION, taskId, deadToken, success()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));

        callbackService.handleCallback(VERSION, taskId, liveToken, success());
        assertThat(pollingService.poll(VERSION, SUBJECT_ID, taskId).status()).isEqualTo(TaskStatus.SUCCESS);

        // terminal 전이가 receipt를 버리므로 그 뒤의 결과 재요청은 다시 401이다.
        assertThatThrownBy(() -> resultService.storeResult(VERSION, taskId, resultToken, resultFrom(input)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
    }

    @Test
    void resultRetry_withDifferentPayload_isIgnoredWithoutChangingStoredGraph() {
        // 재시도 시점의 body는 적용할 경로가 없다(staging 삭제됨 + append-only) — 무시하고 token만
        // 재발급한다. 저장된 결과는 첫 요청의 것 그대로여야 한다.
        String taskId = createDraft(sources());
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, DATE).orElseThrow();
        String taskToken = capturedRequest().taskToken();
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, taskToken);
        String resultToken = input.taskToken();

        resultService.storeResult(VERSION, taskId, resultToken, resultFrom(input, "원래 질문인가요?"));
        AiTimelineResultResponse replay =
                resultService.storeResult(VERSION, taskId, resultToken, resultFrom(input, "다른 질문인가요?"));

        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId()))
                .singleElement()
                .satisfies(event -> assertThat(event.getQuestion()).isEqualTo("원래 질문인가요?"));

        callbackService.handleCallback(VERSION, taskId, replay.taskToken(), success());
        assertThat(pollingService.poll(VERSION, SUBJECT_ID, taskId).status()).isEqualTo(TaskStatus.SUCCESS);
    }

    @Test
    void resultRetry_afterWindowExpiry_isRejectedWithoutTouchingGraph() {
        // 창 만료는 receipt의 절대 마감을 과거로 바꿔 재현한다(실 대기 없이 의미 동일).
        String taskId = createDraft(sources());
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, DATE).orElseThrow();
        String taskToken = capturedRequest().taskToken();
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, taskToken);
        String resultToken = input.taskToken();
        resultService.storeResult(VERSION, taskId, resultToken, resultFrom(input));

        TimelineDraftTask current = taskService.find(taskId).orElseThrow();
        TimelineDraftTask.RetryReceipt receipt = current.retryReceipt();
        assertThat(taskService.saveProcessingIfPresent(taskId,
                current.withRetryReceipt(new TimelineDraftTask.RetryReceipt(
                        receipt.previousTokenHash(), receipt.claimedAt(),
                        Instant.now().minusSeconds(1))))).isTrue();

        assertThatThrownBy(() -> resultService.storeResult(VERSION, taskId, resultToken, resultFrom(input)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));
        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId())).hasSize(1);
    }

    @Test
    void inputRetry_afterLostResponse_reissuesResultTokenAndCompletesChain() {
        // 입력 응답 유실 — AI는 이미 소비한 input token으로 다시 조회하고 새 result token으로 완주한다.
        String taskId = createDraft(sources());
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, DATE).orElseThrow();
        String inputToken = capturedRequest().taskToken();

        AiTimelineTaskInputResponse first = inputService.getInput(VERSION, taskId, inputToken);
        AiTimelineTaskInputResponse retried = inputService.getInput(VERSION, taskId, inputToken);

        assertThat(retried.taskToken()).isNotEqualTo(first.taskToken());
        assertThat(retried.sourceItems()).hasSize(first.sourceItems().size());
        // 직전 result token은 무효다.
        assertThatThrownBy(() -> resultService.storeResult(VERSION, taskId, first.taskToken(), resultFrom(retried)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));

        String callbackToken =
                resultService.storeResult(VERSION, taskId, retried.taskToken(), resultFrom(retried)).taskToken();
        callbackService.handleCallback(VERSION, taskId, callbackToken, success());
        assertThat(pollingService.poll(VERSION, SUBJECT_ID, taskId).status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId())).hasSize(1);
    }

    @Test
    void resultWithUnknownRawId_rollsBackEverything() {
        String taskId = createDraft(sources());
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, DATE).orElseThrow();
        String taskToken = capturedRequest().taskToken();
        AiTimelineTaskInputResponse input = inputService.getInput(VERSION, taskId, taskToken);
        String resultToken = input.taskToken();
        AiTimelineResultRequest invalid = new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.UNKNOWN, "아침", null, null, null, null,
                OffsetDateTime.of(DATE.atTime(9, 0), KST), null, List.of("raw-not-mine"))));

        assertThatThrownBy(() -> resultService.storeResult(VERSION, taskId, resultToken, invalid))
                .isInstanceOf(IllegalArgumentException.class);

        // graph는 롤백되고 Redis stage가 RESULT_PENDING으로 복구돼 올바른 결과를 재시도할 수 있다.
        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId())).isEmpty();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();
        assertThat(taskService.find(taskId).orElseThrow().stage()).isEqualTo(ProcessStage.RESULT_PENDING);
        // 선점 흔적이 남으면 정상 재시도가 409로 막힌다 — 보상은 선점 표식을 지워야 한다.
        // receipt 자체는 입력 회전이 남긴 것이라 그대로 있고, claimedAt만 사라져야 한다.
        assertThat(taskService.find(taskId).orElseThrow().retryReceipt().claimed()).isFalse();

        // 같은 task로 올바른 결과를 다시 보내면 정상 저장된다.
        resultService.storeResult(VERSION, taskId, resultToken, resultFrom(input));
        assertThat(taskService.find(taskId).orElseThrow().stage()).isEqualTo(ProcessStage.CALLBACK_PENDING);
    }

    @Test
    void successCallbackWithoutStoredResult_isRejected1017() {
        String taskId = createDraft(sources());
        String taskToken = capturedRequest().taskToken();
        String resultToken = inputService.getInput(VERSION, taskId, taskToken).taskToken();

        assertThatThrownBy(() -> callbackService.handleCallback(VERSION, taskId, resultToken, success()))
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
        assertThat(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, DATE)).isPresent();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();
    }

    @Test
    void failedCallbackWithResultStageToken_keepsEmptyDraftAndSources() {
        String taskId = createDraft(sources());
        String taskToken = capturedRequest().taskToken();
        String resultToken = inputService.getInput(VERSION, taskId, taskToken).taskToken();

        callbackService.handleCallback(VERSION, taskId, resultToken,
                new DraftTaskCallbackRequest(TaskStatus.FAILED, -1008, "inference failed"));

        DraftTaskStatusResponse status = pollingService.poll(VERSION, SUBJECT_ID, taskId);
        assertThat(status.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(status.error()).isEqualTo(-1008);
        // empty DRAFT는 삭제하지 않는다 — 같은 날짜 재시도가 재사용한다. source는 retention cleanup 대상.
        assertThat(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, DATE)).isPresent();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();

        // 같은 FAILED 재전송은 성공, 상충 결과(SUCCESS)는 -1017이다.
        callbackService.handleCallback(VERSION, taskId, resultToken,
                new DraftTaskCallbackRequest(TaskStatus.FAILED, -1008, "retry"));
        assertThatThrownBy(() -> callbackService.handleCallback(VERSION, taskId, resultToken, success()))
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

        assertThatThrownBy(() -> pollingService.poll(VERSION, SUBJECT_ID, taskId))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1001));
    }

    private String createDraft(List<SourceItemDto> sources) {
        String taskId = draftTaskService.createDraftTask(VERSION, SUBJECT_ID, DATE, RECORD_AT, ZONE, WINDOW, sources);
        createdTaskIds.add(taskId);
        return taskId;
    }

    /** 조회한 입력 전부를 Event 하나로 묶는 최소 결과(실 AI 추론 대행). */
    private AiTimelineResultRequest resultFrom(AiTimelineTaskInputResponse input) {
        return resultFrom(input, QUESTION);
    }

    private AiTimelineResultRequest resultFrom(AiTimelineTaskInputResponse input, String question) {
        return resultFrom(input, question, PLACE, ADDRESS);
    }

    private AiTimelineResultRequest resultFrom(AiTimelineTaskInputResponse input, String question,
                                               String place, String address) {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.UNKNOWN, "아침", null, question, place, address,
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
        return List.of(new SourceItemDto(ItemType.PHOTO, RAW_ID, DATE.atTime(9, 0), null,
                new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://p", 37.5, 127.0, null, null, null, null)));
    }
}
