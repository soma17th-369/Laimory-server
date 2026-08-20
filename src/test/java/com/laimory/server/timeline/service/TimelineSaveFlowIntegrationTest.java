package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.UserMemoryDigest;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateResultRequest;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.MonthlyDailyRecordListResponse;
import com.laimory.server.timeline.dto.MonthlyDailyRecordResponse;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.UserMemoryUpdatePending;
import com.laimory.server.timeline.entity.UserMemoryUpdateTask;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import com.laimory.server.timeline.repository.UserMemoryUpdateTaskStore;
import com.laimory.server.user.repository.UserMemoryRepository;
import com.laimory.server.user.service.UserMemoryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 저장 → User Memory 갱신 전 구간 통합 검증(MySQL + Redis).
 *
 * <p>고정하는 계약 넷:
 * <ul>
 *   <li>저장 API 반환 시점에 record가 이미 SAVED다(동기 저장). 그 하루는 <b>예외 없이</b> 갱신 대기
 *       큐에 들어가고, 요청 경로는 AI를 부르지 않는다.</li>
 *   <li>저장 후에는 편집 경로가 전부 {@code -1003}으로 거절된다(이슈 요구 회귀).</li>
 *   <li>배치가 접수하지 못하면(guard 점유 등) 항목이 큐에 그대로 남아 다음 실행이 가져간다.</li>
 *   <li>AI 결과는 base 지문이 일치할 때만 반영되고, FAILED는 문서를 바꾸지 않는다.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineSaveFlowIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2000, 1, 5);
    private static final LocalDate OTHER_DATE = LocalDate.of(2000, 1, 6);
    private static final String ZONE = "Asia/Seoul";

    @Autowired
    private TimelineSaveService timelineSaveService;
    @Autowired
    private DailyTimelineService dailyTimelineService;
    @Autowired
    private UserMemoryUpdateWorker userMemoryUpdateWorker;
    @Autowired
    private UserMemoryUpdateResultService userMemoryUpdateResultService;
    @Autowired
    private TimelineEventEditService timelineEventEditService;
    @Autowired
    private TimelineDeletionService timelineDeletionService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private UserMemoryRepository userMemoryRepository;
    @Autowired
    private UserMemoryService userMemoryService;
    @Autowired
    private UserMemoryUpdatePendingStore pendingStore;
    @Autowired
    private UserMemoryUpdateTaskStore taskStore;
    @Autowired
    private Clock clock;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 다른 테스트·잔여 데이터와 겹치지 않도록 실행마다 임의 사용자로 격리한다.
    private UUID subjectId;
    private Long recordId;
    private Long eventId;

    @BeforeEach
    void setUp() {
        subjectId = UUID.randomUUID();
        ensureExists(jdbcTemplate, subjectId);
        recordId = dailyRecordRepository.save(DailyRecord.createDraft(subjectId, DATE, DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
        eventId = timelineEventRepository.save(TimelineEvent.of(recordId, TimelineEventType.MEAL,
                        DATE.atTime(12, 10), DATE.atTime(13, 0), "점심", "회사 근처", "점심은 어땠나요?"))
                .getTimelineEventId();
    }

    @AfterEach
    void cleanUp() {
        List.of(DATE, OTHER_DATE).forEach(date -> dailyRecordRepository.findBySubjectIdAndRecordDate(subjectId, date)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId())));
        userMemoryRepository.deleteBySubjectId(subjectId.toString());
        taskStore.releaseGuard(subjectId);
        List<Long> leftover = pendingEntriesOf(subjectId).stream()
                .map(UserMemoryUpdatePending::dailyRecordId)
                .toList();
        if (!leftover.isEmpty()) {
            pendingStore.removeAll(subjectId, leftover);
        }
        // 가입 transaction이 만든 subject 축 push 행(#314)이 남아 있으면 mapping 삭제가 FK RESTRICT에 막힌다.
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, subjectId);
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", subjectId.toString());
    }

    @Test
    void 저장은_즉시_커밋되고_그_하루는_큐에_들어간다() {
        timelineSaveService.save("v1", subjectId, DATE, EmotionType.HAPPY);

        // 반환 시점에 전이와 요청 감정이 같은 행에 이미 커밋돼 있다(동기 저장 — 부분 상태 없음).
        DailyRecord saved = dailyRecordRepository.findById(recordId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(DailyRecordStatus.SAVED);
        assertThat(saved.getEmotionType()).isEqualTo(EmotionType.HAPPY);
        // 접수는 배치가 전담한다 — 저장 경로는 큐에 넣기만 하고 guard도 잡지 않는다.
        assertThat(pendingEntriesOf(subjectId))
                .extracting(UserMemoryUpdatePending::dailyRecordId)
                .contains(recordId);
        assertThat(guardHeldBy(subjectId)).isFalse();
    }

    @Test
    void 저장_후에는_모든_편집이_1003으로_거절된다() {
        timelineSaveService.save("v1", subjectId, DATE, EmotionType.NEUTRAL);

        assertRejectedAsAlreadySaved(() -> timelineEventEditService.updateMemo("v1", subjectId, eventId, "수정"));
        assertRejectedAsAlreadySaved(() -> timelineEventEditService.updateEvent("v1", subjectId, eventId,
                new UpdateTimelineEventRequest("제목", null, DATE.atTime(9, 0), null, null, null, false, List.of())));
        assertRejectedAsAlreadySaved(() -> timelineDeletionService.deleteEvent("v1", subjectId, eventId));
        assertRejectedAsAlreadySaved(() -> timelineDeletionService.deleteDailyRecordByDate("v1", subjectId, DATE));
        assertRejectedAsAlreadySaved(() -> timelineSaveService.save("v1", subjectId, DATE, EmotionType.HAPPY));
    }

    @Test
    void 저장한_감정은_일별_조회와_월별_조회에_함께_보인다() {
        // #304+#298 결합: HAPPY 저장 → 기존 일별 조회는 SAVED+HAPPY, 같은 달 월별 조회는 recordDate+HAPPY.
        timelineSaveService.save("v1", subjectId, DATE, EmotionType.HAPPY);

        DailyTimelineResponse daily = dailyTimelineService.getDailyTimeline("v1", subjectId, DATE);
        assertThat(daily.status()).isEqualTo(DailyRecordStatus.SAVED);
        assertThat(daily.emotionType()).isEqualTo(EmotionType.HAPPY);

        MonthlyDailyRecordListResponse monthly = dailyTimelineService.getMonthlyDailyRecords(
                "v1", subjectId, DATE.getYear(), DATE.getMonthValue());
        assertThat(monthly.dailyRecords())
                .contains(new MonthlyDailyRecordResponse(DATE, EmotionType.HAPPY));
    }

    @Test
    void 저장_실패_요청은_기존_감정을_덮지_않는다() {
        timelineSaveService.save("v1", subjectId, DATE, EmotionType.HAPPY);

        assertRejectedAsAlreadySaved(() -> timelineSaveService.save("v1", subjectId, DATE, EmotionType.VERY_UNHAPPY));

        // 거절된 요청은 상태·감정 어느 쪽도 부분 변경하지 않는다 — 승자의 감정만 남는다.
        DailyRecord saved = dailyRecordRepository.findById(recordId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(DailyRecordStatus.SAVED);
        assertThat(saved.getEmotionType()).isEqualTo(EmotionType.HAPPY);
    }

    @Test
    void 동시_저장은_하나만_성공하고_승자의_감정만_남는다() throws Exception {
        // 사전 검증을 동시에 통과해도 조건부 UPDATE가 유일한 직렬화 지점이라 정확히 한 요청만 1행을 받는다.
        List<EmotionType> emotions = List.of(EmotionType.VERY_HAPPY, EmotionType.VERY_UNHAPPY);
        ExecutorService executor = Executors.newFixedThreadPool(emotions.size());
        CountDownLatch ready = new CountDownLatch(emotions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<EmotionType>> futures;
        try {
            futures = emotions.stream()
                    .map(emotion -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        timelineSaveService.save("v1", subjectId, DATE, emotion);
                        return emotion;
                    }))
                    .toList();
            ready.await();
            start.countDown();
        } finally {
            executor.shutdown();
        }

        List<EmotionType> winners = new java.util.ArrayList<>();
        int conflicts = 0;
        for (Future<EmotionType> future : futures) {
            try {
                winners.add(future.get());
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getExceptionType())
                                .isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED));
                conflicts++;
            }
        }

        assertThat(winners).hasSize(1);
        assertThat(conflicts).isEqualTo(1);
        DailyRecord saved = dailyRecordRepository.findById(recordId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(DailyRecordStatus.SAVED);
        assertThat(saved.getEmotionType()).isEqualTo(winners.get(0));
    }

    @Test
    void 앞선_갱신이_진행_중이면_배치가_접수를_미루고_큐에_남긴다() {
        Long otherRecordId = dailyRecordRepository
                .save(DailyRecord.createDraft(subjectId, OTHER_DATE, OTHER_DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
        // 앞선 날짜가 guard를 잡고 진행 중인 상태를 만든다.
        Instant now = clock.instant();
        UserMemoryUpdatePending inFlight = new UserMemoryUpdatePending(subjectId, otherRecordId);
        pendingStore.enqueue(inFlight, now);
        assertThat(taskStore.acquireGuard(subjectId, "in-flight-task", Duration.ofMinutes(3))).isTrue();

        timelineSaveService.save("v1", subjectId, DATE, EmotionType.NEUTRAL);
        userMemoryUpdateWorker.dispatchPendingUpdates();

        // guard를 못 잡았으니 접수를 미룬다 — 버렸다면 여기가 비어 있다.
        assertThat(pendingEntriesOf(subjectId))
                .extracting(UserMemoryUpdatePending::dailyRecordId)
                .contains(recordId);

        taskStore.releaseGuard(subjectId);
        userMemoryUpdateWorker.dispatchPendingUpdates();

        // 접수한 뒤에도 큐를 비우지 않는다 — 반영 확인과 정리는 결과 endpoint 몫이다.
        // noop dispatcher라 결과가 오지 않으므로 항목이 그대로 남아 있어야 한다.
        assertThat(pendingEntriesOf(subjectId))
                .extracting(UserMemoryUpdatePending::dailyRecordId)
                .contains(recordId);
    }

    @Test
    void 반영이_확인되면_결과_endpoint가_큐에서_지운다() throws Exception {
        JsonNode updated = objectMapper.readTree("{\"schemaVersion\":\"1.0\"}");
        pendingStore.enqueue(new UserMemoryUpdatePending(subjectId, recordId), clock.instant());

        String taskId = UUID.randomUUID().toString();
        String token = TaskTokens.generate();
        taskStore.save(taskId, new UserMemoryUpdateTask(subjectId, List.of(recordId), TaskTokens.hash(token),
                clock.instant(), UserMemoryDigest.of(Optional.empty())), Duration.ofMinutes(3));

        userMemoryUpdateResultService.applyResult("v1", taskId, token,
                new AiUserMemoryUpdateResultRequest("SUCCESS", updated, null, null));

        assertThat(pendingEntriesOf(subjectId)).isEmpty();
    }

    @Test
    void AI가_실패를_통보하면_결과_endpoint가_큐에_넣어_배치가_재시도하게_한다() {
        String taskId = UUID.randomUUID().toString();
        String token = TaskTokens.generate();
        taskStore.save(taskId, new UserMemoryUpdateTask(subjectId, List.of(recordId), TaskTokens.hash(token),
                clock.instant(), null), Duration.ofMinutes(3));

        userMemoryUpdateResultService.applyResult("v1", taskId, token,
                new AiUserMemoryUpdateResultRequest("FAILED", null, 1210, "budget exceeded"));

        // 반영 확인 전에 큐에서 빠질 길이 없지만, 실패 통보도 같은 안전망을 다시 건다.
        assertThat(pendingEntriesOf(subjectId))
                .extracting(UserMemoryUpdatePending::dailyRecordId)
                .contains(recordId);
    }

    @Test
    void AI_결과는_base_지문이_맞을_때만_반영된다() throws Exception {
        JsonNode updated = objectMapper.readTree("{\"schemaVersion\":\"1.0\",\"currentFocus\":\"이사 준비\"}");
        String taskId = UUID.randomUUID().toString();
        String token = TaskTokens.generate();
        taskStore.save(taskId, new UserMemoryUpdateTask(subjectId, List.of(recordId), TaskTokens.hash(token),
                clock.instant(), UserMemoryDigest.of(Optional.empty())), Duration.ofMinutes(3));

        userMemoryUpdateResultService.applyResult("v1", taskId,
                token, new AiUserMemoryUpdateResultRequest("SUCCESS", updated, null, null));

        assertThat(userMemoryService.find(subjectId)).contains(updated);
        assertThat(taskStore.find(taskId)).isEmpty(); // 종결 = 삭제. 중복 결과는 404가 된다.
    }

    @Test
    void base_문서가_그_사이_교체됐으면_결과를_폐기한다() throws Exception {
        JsonNode replacedByAnotherDay = objectMapper.readTree("{\"schemaVersion\":\"1.0\",\"currentFocus\":\"1/4\"}");
        userMemoryService.replace(subjectId, replacedByAnotherDay);

        String taskId = UUID.randomUUID().toString();
        String token = TaskTokens.generate();
        // 접수 시점엔 문서가 없었다고 기록한다 → 현재 문서와 지문이 다르다.
        taskStore.save(taskId, new UserMemoryUpdateTask(subjectId, List.of(recordId), TaskTokens.hash(token),
                clock.instant(), null), Duration.ofMinutes(3));

        assertThatThrownBy(() -> userMemoryUpdateResultService.applyResult("v1", taskId, token,
                new AiUserMemoryUpdateResultRequest("SUCCESS",
                        objectMapper.readTree("{\"schemaVersion\":\"1.0\"}"), null, null)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(-1017));

        assertThat(userMemoryService.find(subjectId)).contains(replacedByAnotherDay);
    }

    @Test
    void FAILED_통보는_문서를_바꾸지_않고_record도_SAVED로_남긴다() throws Exception {
        JsonNode existing = objectMapper.readTree("{\"schemaVersion\":\"1.0\",\"currentFocus\":\"그대로\"}");
        userMemoryService.replace(subjectId, existing);
        timelineSaveService.save("v1", subjectId, DATE, EmotionType.NEUTRAL);

        String taskId = UUID.randomUUID().toString();
        String token = TaskTokens.generate();
        taskStore.save(taskId, new UserMemoryUpdateTask(subjectId, List.of(recordId), TaskTokens.hash(token),
                clock.instant(), UserMemoryDigest.of(Optional.of(existing))), Duration.ofMinutes(3));

        userMemoryUpdateResultService.applyResult("v1", taskId, token,
                new AiUserMemoryUpdateResultRequest("FAILED", null, 1210, "budget exceeded"));

        assertThat(userMemoryService.find(subjectId)).contains(existing);
        assertThat(dailyRecordRepository.findById(recordId).orElseThrow().getStatus())
                .isEqualTo(DailyRecordStatus.SAVED);
    }

    /** guard는 획득 시도로만 관측한다 — 잡히면 비어 있었다는 뜻이라 곧바로 되돌린다. */
    private boolean guardHeldBy(UUID ownerId) {
        if (taskStore.acquireGuard(ownerId, "probe", Duration.ofSeconds(5))) {
            taskStore.releaseGuard(ownerId);
            return false;
        }
        return true;
    }

    private List<UserMemoryUpdatePending> pendingEntriesOf(UUID ownerId) {
        return pendingStore.findPending(clock.instant().plusSeconds(3600), 1000).scanned().stream()
                .filter(pending -> pending.subjectId().equals(ownerId))
                .toList();
    }

    private void assertRejectedAsAlreadySaved(Runnable edit) {
        assertThatThrownBy(edit::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1003);
                });
    }
}
