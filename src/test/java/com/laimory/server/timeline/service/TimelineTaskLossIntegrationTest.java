package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.service.CreditService;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.testsupport.TestSubjects;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineTaskStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 유실 타이머 만료 → 유실 처리(#565·#593)의 실 MySQL·Redis 검증. 타이머 만료가 keyspace 알림으로 구독자에게 오는 배선은
 * 짧은 TTL 타이머로 확인하고(Compose Redis의 {@code notify-keyspace-events Kx} 필요), 처리·건너뛰는 경로(환불·소비된
 * 기록·다른 서버가 잡은 잠금)는 알림 대기 없이 수신자를 직접 호출해 결정적으로 확인한다.
 *
 * <p>통합 테스트 JVM에는 캐시된 Spring 컨텍스트가 여럿 살아 있고 컨텍스트마다 구독자가 있다 — 실제 만료 알림은 그중
 * 유실 처리 잠금을 잡은 아무 컨텍스트가 처리한다. 그래서 배선 테스트는 어느 컨텍스트가 처리해도 같은 DB·Redis 결과만 확인하고,
 * 이 컨텍스트의 mock에 기대는 push 호출 확인은 수신자를 직접 부르는 테스트에서만 한다.
 *
 * <p>완료 push는 FCM 외부 호출이라 mock으로 바꾸고, 호출 자체가 계약이라 {@code verify}로 확인한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineTaskLossIntegrationTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(565_001L);

    @Autowired
    private CreditService creditService;
    @Autowired
    private TimelineTaskLossHandler handler;
    @Autowired
    private TimelineTaskLossTimerService timelineTaskLossTimerService;
    @Autowired
    private TimelineDraftTaskPollingService pollingService;
    @Autowired
    private TimelineTaskStore timelineTaskStore;
    @Autowired
    private RedisGateway redis;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TimelineCompletionPushNotifier pushNotifier;

    private String taskId;

    @BeforeEach
    void setUp() {
        ensureExists(jdbcTemplate, SUBJECT_ID);
        SubjectMappingFixtures.ensureCredits(jdbcTemplate, SUBJECT_ID, 60);
        taskId = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanUp() {
        redis.delete("timeline:draft-task:loss-timer:" + taskId);
        redis.delete("timeline:draft-task:loss-lock:" + taskId);
        redis.delete("timeline:draft-task:" + taskId);
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, SUBJECT_ID);
        jdbcTemplate.update("DELETE FROM daily_records WHERE subject_id = ?", SUBJECT_ID.toString());
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", SUBJECT_ID.toString());
    }

    @Test
    void expiredLossTimerIsDeliveredAndFailsLiveTask() {
        long recordId = emptyDraft(LocalDate.of(2026, 10, 10));
        creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, taskId);
        saveProcessing(recordId);

        redis.set("timeline:draft-task:loss-timer:" + taskId, "", Duration.ofMillis(100));

        // 환불·삭제 commit 뒤 FAILED를 쓰므로 FAILED가 보일 때까지 기다린다 — 처리한 컨텍스트는 어느 것이든 된다.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(timelineTaskStore.find(taskId).orElseThrow().status()).isEqualTo(TaskStatus.FAILED));
        assertThat(historyStatus()).isEqualTo("REFUNDED");
        assertThat(remainingCredits()).isEqualTo(60);
        assertThat(recordModifiedBy(recordId)).isEqualTo("ai_failed");
        DraftTaskStatusResponse polled = pollingService.poll("v1", SUBJECT_ID, taskId);
        assertThat(polled.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(polled.error()).isEqualTo(-1008);
    }

    @Test
    void expirationOfLiveChargedTaskRefundsDeletesEmptyDraftFailsTaskAndPushesFailure() {
        long recordId = emptyDraft(LocalDate.of(2026, 10, 11));
        creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, taskId);
        saveProcessing(recordId);

        handler.onExpired("timeline:draft-task:loss-timer:" + taskId);

        assertThat(remainingCredits()).isEqualTo(60);
        assertThat(jdbcTemplate.queryForObject("SELECT refund_reason FROM credit_histories WHERE reference_id = ?",
                String.class, taskId)).isEqualTo("TIMEOUT");
        assertThat(recordModifiedBy(recordId)).isEqualTo("ai_failed");
        DraftTaskStatusResponse polled = pollingService.poll("v1", SUBJECT_ID, taskId);
        assertThat(polled.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(polled.error()).isEqualTo(-1008);
        verify(pushNotifier).notifyAsync(SUBJECT_ID, taskId, TaskStatus.FAILED);
    }

    @Test
    void expirationOfChargedTaskWithoutTaskRefundsWithoutFailedTaskOrPush() {
        // 배포 중 옛 서버가 만든 task(3분 TTL)는 타이머와 함께 사라진다 — 환불만 하고 FAILED·push는 남기지 않는다.
        creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, taskId);

        handler.onExpired("timeline:draft-task:loss-timer:" + taskId);

        assertThat(remainingCredits()).isEqualTo(60);
        assertThat(historyStatus()).isEqualTo("REFUNDED");
        assertThat(timelineTaskStore.find(taskId)).isEmpty();
        verify(pushNotifier, never()).notifyAsync(any(), anyString(), any());
    }

    @Test
    void expirationOfConsumedChargeKeepsSuccessWithoutPush() {
        // 결과 저장 뒤 타이머 삭제가 실패해 온 알림 — 성공한 생성을 실패로 덮거나 환불·실패 push를 보내지 않는다.
        creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, taskId);
        creditService.consume(CreditCostType.TIMELINE_CREATION, taskId);
        timelineTaskStore.save(taskId, TimelineDraftTask.success(SUBJECT_ID, 1L, "hash"), Duration.ofMinutes(1));

        handler.onExpired("timeline:draft-task:loss-timer:" + taskId);

        assertThat(remainingCredits()).isEqualTo(60 - timelineCreationCost());
        assertThat(historyStatus()).isEqualTo("CONSUMED");
        assertThat(timelineTaskStore.find(taskId).orElseThrow().status()).isEqualTo(TaskStatus.SUCCESS);
        verify(pushNotifier, never()).notifyAsync(any(), anyString(), any());
    }

    @Test
    void expirationWhileAnotherServerHoldsRefundLockChangesNothing() {
        // 같은 알림을 받은 다른 서버가 잠금을 먼저 잡았다 — 이 서버는 환불·기록·push를 하지 않는다.
        creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, taskId);
        timelineTaskLossTimerService.tryLock(taskId);

        handler.onExpired("timeline:draft-task:loss-timer:" + taskId);

        assertThat(remainingCredits()).isEqualTo(60 - timelineCreationCost());
        assertThat(historyStatus()).isEqualTo("CHARGED");
        assertThat(timelineTaskStore.find(taskId)).isEmpty();
        verify(pushNotifier, never()).notifyAsync(any(), anyString(), any());
    }

    private long emptyDraft(LocalDate recordDate) {
        return dailyRecordRepository.save(DailyRecord.createDraft(
                SUBJECT_ID, recordDate, recordDate.atTime(12, 0), "Asia/Seoul")).getDailyRecordId();
    }

    private void saveProcessing(long recordId) {
        timelineTaskStore.save(taskId, TimelineDraftTask.processing(SUBJECT_ID, recordId,
                new TimelineDraftTask.TimelineWindow(LocalDateTime.of(2026, 10, 10, 0, 0),
                        LocalDateTime.of(2026, 10, 10, 23, 0)), "hash", Instant.now()),
                Duration.ofMinutes(1));
    }

    private String recordModifiedBy(long recordId) {
        return jdbcTemplate.queryForObject(
                "SELECT modified_by FROM daily_records WHERE daily_record_id = ? AND deleted_at IS NOT NULL",
                String.class, recordId);
    }

    private String historyStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM credit_histories WHERE reference_id = ?",
                String.class, taskId);
    }

    private int remainingCredits() {
        return jdbcTemplate.queryForObject("SELECT remaining FROM subject_credits WHERE subject_id = ?",
                Integer.class, SUBJECT_ID.toString());
    }

    /** 기대 차감액은 seed된 비용 행 기준이다 — 공유 DB의 비용 행은 테스트가 바꾸지 않는다(#558). */
    private int timelineCreationCost() {
        return jdbcTemplate.queryForObject("SELECT cost FROM credit_costs WHERE type = 'TIMELINE_CREATION'",
                Integer.class);
    }
}
