package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.dto.TimelineWindowDto;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.TimelineDraftEventSuggestion;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineDraftEventSuggestionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Callback-Token + DB-경유 draft 흐름 end-to-end 검증(실 MySQL+Redis). 수동 curl을 대체한다.
 * 토큰은 불투명하므로 디스패처를 spy해 발급된 raw 토큰을 캡처한 뒤 콜백을 호출한다.
 * events는 콜백 바디로 오지 않으므로, AI의 write-then-notify를 시뮬레이션해 이벤트 제안을 DB에 심고
 * source item을 그 이벤트에 배정한 뒤 콜백한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineCallbackTokenIntegrationTest {

    @Autowired
    private TimelineDraftTaskService draftTaskService;
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
    private TimelineDraftEventSuggestionService eventSuggestionService;
    @Autowired
    private TimelineDraftEventSuggestionRepository eventSuggestionRepository;
    @Autowired
    private RedisGateway redis;

    @MockitoSpyBean
    private TimelineEventSuggestionDispatcher dispatcher;

    private static final String VERSION = "v1";
    private static final long USER_ID = 7L;
    private static final String ZONE = "Asia/Seoul";
    // 다른 데이터와 충돌하지 않을 고정 날짜 — 클라 선택 날짜로 요청에 명시 전송한다(서버 파생 없음).
    private static final LocalDate DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDateTime RECORD_AT = LocalDateTime.of(2000, 1, 1, 12, 0); // 실제 작성 시각 메타데이터
    private static final TimelineWindowDto WINDOW = new TimelineWindowDto(
            LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2000, 1, 2, 0, 0));

    private final List<String> createdTaskIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId())); // DB FK cascade로 이벤트/아이템도 삭제
        createdTaskIds.forEach(id -> {
            draftSourceItemService.deleteByTaskId(id);
            eventSuggestionService.deleteByTaskId(id);
            redis.delete("timeline:draft-task:" + id);
            redis.delete("timeline:callback-token-uses:" + id); // 토큰 소비 카운터(TTL 25h) — 테스트 Redis 잔존 방지
            // redis는 RedisGateway → 환경 prefix는 내부에서 자동 부착(논리 키만 넘김).
        });
        createdTaskIds.clear();
        // 날짜 guard: terminal에 못 간 테스트(wrongToken 등)의 task는 guard를 쥔 채 남는다(운영에선 TTL 1h 해제).
        // 이 클래스는 같은 고정 날짜를 공유하므로 다음 테스트의 draft 생성이 1016으로 막히지 않게 지운다.
        redis.delete("timeline:date-guard:" + USER_ID + ":" + DATE);
    }

    @Test
    void validToken_persistsFinalizesAndDeletesStaging_storesOnlyHash() {
        String taskId = draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources());
        createdTaskIds.add(taskId);

        // POST 시점에 source 행이 MySQL에 저장돼 있다(아직 daily_records 없음).
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();
        assertThat(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).isEmpty();

        // 디스패처 spy로 발급된 raw 토큰 캡처(2-arg dispatch).
        String token = capturedToken(taskId);

        // Redis에는 원문 토큰이 없고 해시만 보관된다. task owner는 요청자 userId로 저장된다.
        String rawJson = redis.get("timeline:draft-task:" + taskId);
        assertThat(rawJson).doesNotContain(token);
        TimelineDraftTask stored = taskService.find(taskId).orElseThrow();
        assertThat(stored.callbackTokenHash()).isNotNull().isNotEqualTo(token);
        assertThat(CallbackTokens.matches(token, stored.callbackTokenHash())).isTrue();
        assertThat(stored.userId()).isEqualTo(USER_ID);

        // AI 시뮬(write-then-notify): 이벤트 제안을 DB에 저장하고 source item을 그 이벤트에 배정한다.
        simulateAiWrite(taskId);

        // 유효 토큰으로 콜백(바디엔 status만) → SUCCESS + MySQL 영속 + staging 삭제.
        callbackService.handleCallback(VERSION, taskId, token, success());

        DraftTaskStatusResponse status = pollingService.poll(VERSION, USER_ID, taskId);
        assertThat(status.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(status.result().events()).isNotEmpty();
        assertThat(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).isPresent();
        // finalize가 소비한 staging(source item + event 제안)을 둘 다 삭제했다.
        assertThat(draftSourceItemService.findByTaskId(taskId)).isEmpty();
        assertThat(eventSuggestionService.findByTaskId(taskId)).isEmpty();
        // 종결 후에도 해시와 owner가 보존된다(terminal 재콜백 token-first 검증 + 폴링 소유권 대조용).
        TimelineDraftTask terminal = taskService.find(taskId).orElseThrow();
        assertThat(terminal.callbackTokenHash()).isEqualTo(stored.callbackTokenHash());
        assertThat(terminal.userId()).isEqualTo(USER_ID);

        // 재콜백(같은 토큰) → 원자적 소비 게이트가 401 ERROR_1012로 거부(1002=불일치와 구분되는 replay 전용 코드).
        assertThatThrownBy(() -> callbackService.handleCallback(VERSION, taskId, token, success()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1012));
        // 거부된 replay는 상태를 건드리지 않는다: SUCCESS 유지 + 이벤트 중복 없음.
        DraftTaskStatusResponse afterReplay = pollingService.poll(VERSION, USER_ID, taskId);
        assertThat(afterReplay.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(afterReplay.result().events()).hasSameSizeAs(status.result().events());
    }

    @Test
    void concurrentCallbacks_exactlyOneWins_otherRejected1012() throws Exception {
        String taskId = draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources());
        createdTaskIds.add(taskId);
        String token = capturedToken(taskId);
        simulateAiWrite(taskId);

        // 같은 토큰으로 콜백 2개를 동시에 발사 — INCR 원자성으로 정확히 하나만 승자.
        CountDownLatch start = new CountDownLatch(1);
        Callable<BusinessException> callback = () -> {
            start.await();
            try {
                callbackService.handleCallback(VERSION, taskId, token, success());
                return null; // 성공
            } catch (BusinessException e) {
                return e;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BusinessException> first = pool.submit(callback);
            Future<BusinessException> second = pool.submit(callback);
            start.countDown();
            // 두 future를 모두 join한 뒤에만 단언한다(승자의 finalize 완료가 보장된 시점).
            List<BusinessException> results = new ArrayList<>(List.of());
            results.add(first.get(30, TimeUnit.SECONDS));
            results.add(second.get(30, TimeUnit.SECONDS));

            long successes = results.stream().filter(Objects::isNull).count();
            assertThat(successes).isEqualTo(1);
            BusinessException rejected = results.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            assertThat(rejected.getErrorCode()).isEqualTo(ErrorCode.ERROR_1012);
        } finally {
            pool.shutdownNow();
        }

        // 최종 상태는 SUCCESS 하나로 수렴, 이벤트 중복 없음.
        DraftTaskStatusResponse status = pollingService.poll(VERSION, USER_ID, taskId);
        assertThat(status.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(status.result().events()).hasSize(1);
    }

    @Test
    void wrongToken_rejected401_andNothingPersisted() {
        String taskId = draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources());
        createdTaskIds.add(taskId);

        assertThatThrownBy(() -> callbackService.handleCallback(VERSION, taskId, "wrong-token", success()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_1002));

        // task는 여전히 PROCESSING, MySQL daily_records엔 아무것도 안 써짐, source는 보존.
        assertThat(taskService.find(taskId).orElseThrow().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).isEmpty();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();
    }

    @Test
    void successCallback_eventWithNoLinkedSourceItems_marksFailed() {
        String taskId = draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources());
        createdTaskIds.add(taskId);
        String token = capturedToken(taskId);

        // AI 시뮬: 이벤트 제안은 저장하되 source item을 하나도 배정하지 않는다(event_fk 미설정).
        eventSuggestionRepository.save(
                TimelineDraftEventSuggestion.of(taskId, USER_ID, TimelineEventType.UNKNOWN.name(), DATE.atTime(9, 0), null, "빈 이벤트", null));

        callbackService.handleCallback(VERSION, taskId, token, success());

        // 이벤트에 묶인 item 0개 → assembler가 빈 itemIds → validator 'event has no itemIds' → FAILED, record 미생성.
        DraftTaskStatusResponse status = pollingService.poll(VERSION, USER_ID, taskId);
        assertThat(status.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).isEmpty();
    }

    /** AI write-then-notify 시뮬: 이벤트 제안 1건을 저장하고 task의 모든 source item을 그 이벤트에 배정한다. */
    private void simulateAiWrite(String taskId) {
        TimelineDraftEventSuggestion event = eventSuggestionRepository.save(
                TimelineDraftEventSuggestion.of(taskId, USER_ID, TimelineEventType.UNKNOWN.name(), DATE.atTime(9, 0), null, "아침", null));
        List<TimelineDraftSourceItem> rows = draftSourceItemService.findByTaskId(taskId);
        rows.forEach(r -> r.assignEventSuggestion(event.getTimelineDraftEventSuggestionId()));
        draftSourceItemService.saveAll(rows);
    }

    private String capturedToken(String taskId) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).dispatch(eq(taskId), captor.capture());
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
