package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.UserMemoryDigest;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateResultRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.UserMemoryUpdatePending;
import com.laimory.server.timeline.entity.UserMemoryUpdateTask;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import com.laimory.server.timeline.repository.UserMemoryUpdateTaskStore;
import com.laimory.server.user.UserMemoryRepository;
import com.laimory.server.user.UserMemoryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 다른 테스트·잔여 데이터와 겹치지 않도록 실행마다 임의 사용자로 격리한다.
    private long userId;
    private Long recordId;
    private Long eventId;

    @BeforeEach
    void setUp() {
        userId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        recordId = dailyRecordRepository.save(DailyRecord.createDraft(userId, DATE, DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
        eventId = timelineEventRepository.save(TimelineEvent.of(recordId, TimelineEventType.MEAL,
                        DATE.atTime(12, 10), DATE.atTime(13, 0), "점심", "회사 근처", "점심은 어땠나요?"))
                .getTimelineEventId();
    }

    @AfterEach
    void cleanUp() {
        List.of(DATE, OTHER_DATE).forEach(date -> dailyRecordRepository.findByUserIdAndRecordDate(userId, date)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId())));
        userMemoryRepository.deleteByUserId(userId);
        taskStore.releaseGuard(userId);
        List<Long> leftover = pendingEntriesOf(userId).stream()
                .map(UserMemoryUpdatePending::dailyRecordId)
                .toList();
        if (!leftover.isEmpty()) {
            pendingStore.removeAll(userId, leftover);
        }
    }

    @Test
    void 저장은_즉시_커밋되고_그_하루는_큐에_들어간다() {
        timelineSaveService.save("v1", userId, DATE);

        // 반환 시점에 전이는 이미 커밋돼 있다(동기 저장).
        assertThat(dailyRecordRepository.findById(recordId).orElseThrow().getStatus())
                .isEqualTo(DailyRecordStatus.SAVED);
        // 접수는 배치가 전담한다 — 저장 경로는 큐에 넣기만 하고 guard도 잡지 않는다.
        assertThat(pendingEntriesOf(userId))
                .extracting(UserMemoryUpdatePending::dailyRecordId)
                .contains(recordId);
        assertThat(guardHeldBy(userId)).isFalse();
    }

    @Test
    void 저장_후에는_모든_편집이_1003으로_거절된다() {
        timelineSaveService.save("v1", userId, DATE);

        assertRejectedAsAlreadySaved(() -> timelineEventEditService.updateMemo("v1", userId, eventId, "수정"));
        assertRejectedAsAlreadySaved(() -> timelineEventEditService.updateEvent("v1", userId, eventId,
                new UpdateTimelineEventRequest("제목", null, DATE.atTime(9, 0), null, null, null, false, List.of())));
        assertRejectedAsAlreadySaved(() -> timelineDeletionService.deleteEvent("v1", userId, eventId));
        assertRejectedAsAlreadySaved(() -> timelineDeletionService.deleteDailyRecordByDate("v1", userId, DATE));
        assertRejectedAsAlreadySaved(() -> timelineSaveService.save("v1", userId, DATE));
    }

    @Test
    void 앞선_갱신이_진행_중이면_배치가_접수를_미루고_큐에_남긴다() {
        Long otherRecordId = dailyRecordRepository
                .save(DailyRecord.createDraft(userId, OTHER_DATE, OTHER_DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
        // 앞선 날짜가 guard를 잡고 진행 중인 상태를 만든다.
        Instant now = clock.instant();
        UserMemoryUpdatePending inFlight = new UserMemoryUpdatePending(userId, otherRecordId);
        pendingStore.enqueue(inFlight, now);
        assertThat(taskStore.acquireGuard(userId, "in-flight-task", Duration.ofMinutes(3))).isTrue();

        timelineSaveService.save("v1", userId, DATE);
        userMemoryUpdateWorker.dispatchPendingUpdates();

        // guard를 못 잡았으니 접수를 미룬다 — 버렸다면 여기가 비어 있다.
        assertThat(pendingEntriesOf(userId))
                .extracting(UserMemoryUpdatePending::dailyRecordId)
                .contains(recordId);

        taskStore.releaseGuard(userId);
        userMemoryUpdateWorker.dispatchPendingUpdates();

        // 접수한 뒤에도 큐를 비우지 않는다 — 반영 확인과 정리는 결과 endpoint 몫이다.
        // noop dispatcher라 결과가 오지 않으므로 항목이 그대로 남아 있어야 한다.
        assertThat(pendingEntriesOf(userId))
                .extracting(UserMemoryUpdatePending::dailyRecordId)
                .contains(recordId);
    }

    @Test
    void 반영이_확인되면_결과_endpoint가_큐에서_지운다() throws Exception {
        JsonNode updated = objectMapper.readTree("{\"schemaVersion\":\"1.0\"}");
        pendingStore.enqueue(new UserMemoryUpdatePending(userId, recordId), clock.instant());

        String taskId = UUID.randomUUID().toString();
        String token = TaskTokens.generate();
        taskStore.save(taskId, new UserMemoryUpdateTask(userId, List.of(recordId), TaskTokens.hash(token),
                clock.instant(), UserMemoryDigest.of(Optional.empty())), Duration.ofMinutes(3));

        userMemoryUpdateResultService.applyResult("v1", taskId, token,
                new AiUserMemoryUpdateResultRequest("SUCCESS", updated, null, null));

        assertThat(pendingEntriesOf(userId)).isEmpty();
    }

    @Test
    void AI가_실패를_통보하면_결과_endpoint가_큐에_넣어_배치가_재시도하게_한다() {
        String taskId = UUID.randomUUID().toString();
        String token = TaskTokens.generate();
        taskStore.save(taskId, new UserMemoryUpdateTask(userId, List.of(recordId), TaskTokens.hash(token),
                clock.instant(), null), Duration.ofMinutes(3));

        userMemoryUpdateResultService.applyResult("v1", taskId, token,
                new AiUserMemoryUpdateResultRequest("FAILED", null, 1210, "budget exceeded"));

        // 반영 확인 전에 큐에서 빠질 길이 없지만, 실패 통보도 같은 안전망을 다시 건다.
        assertThat(pendingEntriesOf(userId))
                .extracting(UserMemoryUpdatePending::dailyRecordId)
                .contains(recordId);
    }

    @Test
    void AI_결과는_base_지문이_맞을_때만_반영된다() throws Exception {
        JsonNode updated = objectMapper.readTree("{\"schemaVersion\":\"1.0\",\"currentFocus\":\"이사 준비\"}");
        String taskId = UUID.randomUUID().toString();
        String token = TaskTokens.generate();
        taskStore.save(taskId, new UserMemoryUpdateTask(userId, List.of(recordId), TaskTokens.hash(token),
                clock.instant(), UserMemoryDigest.of(Optional.empty())), Duration.ofMinutes(3));

        userMemoryUpdateResultService.applyResult("v1", taskId,
                token, new AiUserMemoryUpdateResultRequest("SUCCESS", updated, null, null));

        assertThat(userMemoryService.find(userId)).contains(updated);
        assertThat(taskStore.find(taskId)).isEmpty(); // 종결 = 삭제. 중복 결과는 404가 된다.
    }

    @Test
    void base_문서가_그_사이_교체됐으면_결과를_폐기한다() throws Exception {
        JsonNode replacedByAnotherDay = objectMapper.readTree("{\"schemaVersion\":\"1.0\",\"currentFocus\":\"1/4\"}");
        userMemoryService.replace(userId, replacedByAnotherDay);

        String taskId = UUID.randomUUID().toString();
        String token = TaskTokens.generate();
        // 접수 시점엔 문서가 없었다고 기록한다 → 현재 문서와 지문이 다르다.
        taskStore.save(taskId, new UserMemoryUpdateTask(userId, List.of(recordId), TaskTokens.hash(token),
                clock.instant(), null), Duration.ofMinutes(3));

        assertThatThrownBy(() -> userMemoryUpdateResultService.applyResult("v1", taskId, token,
                new AiUserMemoryUpdateResultRequest("SUCCESS",
                        objectMapper.readTree("{\"schemaVersion\":\"1.0\"}"), null, null)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(-1017));

        assertThat(userMemoryService.find(userId)).contains(replacedByAnotherDay);
    }

    @Test
    void FAILED_통보는_문서를_바꾸지_않고_record도_SAVED로_남긴다() throws Exception {
        JsonNode existing = objectMapper.readTree("{\"schemaVersion\":\"1.0\",\"currentFocus\":\"그대로\"}");
        userMemoryService.replace(userId, existing);
        timelineSaveService.save("v1", userId, DATE);

        String taskId = UUID.randomUUID().toString();
        String token = TaskTokens.generate();
        taskStore.save(taskId, new UserMemoryUpdateTask(userId, List.of(recordId), TaskTokens.hash(token),
                clock.instant(), UserMemoryDigest.of(Optional.of(existing))), Duration.ofMinutes(3));

        userMemoryUpdateResultService.applyResult("v1", taskId, token,
                new AiUserMemoryUpdateResultRequest("FAILED", null, 1210, "budget exceeded"));

        assertThat(userMemoryService.find(userId)).contains(existing);
        assertThat(dailyRecordRepository.findById(recordId).orElseThrow().getStatus())
                .isEqualTo(DailyRecordStatus.SAVED);
    }

    /** guard는 획득 시도로만 관측한다 — 잡히면 비어 있었다는 뜻이라 곧바로 되돌린다. */
    private boolean guardHeldBy(long ownerId) {
        if (taskStore.acquireGuard(ownerId, "probe", Duration.ofSeconds(5))) {
            taskStore.releaseGuard(ownerId);
            return false;
        }
        return true;
    }

    private List<UserMemoryUpdatePending> pendingEntriesOf(long ownerId) {
        return pendingStore.findPending(clock.instant().plusSeconds(3600), 1000).scanned().stream()
                .filter(pending -> pending.userId() == ownerId)
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
