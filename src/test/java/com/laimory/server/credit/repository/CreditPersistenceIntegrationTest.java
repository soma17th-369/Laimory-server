package com.laimory.server.credit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.CreditHistoryStatus;
import com.laimory.server.credit.CreditRefundReason;
import com.laimory.server.credit.service.CreditService;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.testsupport.TestSubjects;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * subject_credits·credit_histories 실 MySQL 왕복 검증(#548·#565) — 조건부 차감이 잔액을 넘지 않는 것, 사용 기록의
 * 소비·환불 전이와 환불이 한 번만 일어나는 것, 가입 재실행이 잔액을 되돌리지 않는 것, 음수 CHECK와 삭제를 실제
 * 제약 위에서 확인한다. 최근 AI 실패 경보(#617)의 결말 난 기록 창 조회도 실제 정렬·LIMIT로 확인한다.
 * credit_costs(#558)는 migration seed가 모든 비용 종류를
 * 덮는지 확인한다. {@code ddl-auto=validate}라 컨텍스트 기동 자체가
 * 엔티티↔DDL 정합을 검증한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class CreditPersistenceIntegrationTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(948_001L);
    private static final String TASK_ID = "00000000-0000-7000-8000-000000948001";

    @Autowired
    private CreditService creditService;

    @Autowired
    private SubjectCreditRepository subjectCreditRepository;

    @Autowired
    private CreditHistoryRepository creditHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void givenSubject() {
        SubjectMappingFixtures.ensureExists(jdbcTemplate, SUBJECT_ID);
    }

    @AfterEach
    void cleanUp() {
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, SUBJECT_ID);
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", SUBJECT_ID.toString());
    }

    private void givenRemaining(int remaining) {
        creditService.createDefaultIfAbsent(SUBJECT_ID);
        jdbcTemplate.update("UPDATE subject_credits SET remaining = ? WHERE subject_id = ?",
                remaining, SUBJECT_ID.toString());
    }

    private int remaining() {
        return jdbcTemplate.queryForObject("SELECT remaining FROM subject_credits WHERE subject_id = ?",
                Integer.class, SUBJECT_ID.toString());
    }

    private Map<String, Object> history() {
        return jdbcTemplate.queryForMap("SELECT subject_id, amount, status, refund_reason FROM credit_histories "
                + "WHERE cost_type = 'TIMELINE_CREATION' AND reference_id = ?", TASK_ID);
    }

    private int historyCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM credit_histories WHERE subject_id = ?",
                Integer.class, SUBJECT_ID.toString());
    }

    /** 기대 차감액은 seed된 비용 행 기준이다 — 공유 DB의 비용 행은 테스트가 바꾸지 않는다(#558). */
    private int timelineCreationCost() {
        return jdbcTemplate.queryForObject("SELECT cost FROM credit_costs WHERE type = 'TIMELINE_CREATION'",
                Integer.class);
    }

    @Test
    void chargeDecreasesRemainingByCostAndRecordsCharged() {
        creditService.createDefaultIfAbsent(SUBJECT_ID);

        creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, TASK_ID);

        assertThat(remaining()).isEqualTo(30 - timelineCreationCost());
        assertThat(history()).containsEntry("subject_id", SUBJECT_ID.toString())
                .containsEntry("amount", timelineCreationCost())
                .containsEntry("status", "CHARGED")
                .containsEntry("refund_reason", null);
    }

    @Test
    void everyCreditCostTypeHasSeededCostRow() {
        // 종류를 추가하면서 migration seed를 빠뜨리면 그 기능의 사전 검사·차감이 500이 된다 — 배포 전에 잡는다.
        List<String> seededTypes = jdbcTemplate.queryForList("SELECT type FROM credit_costs", String.class);

        assertThat(seededTypes).containsAll(
                Arrays.stream(CreditCostType.values()).map(Enum::name).toList());
    }

    @Test
    void chargeBelowCostIsRejectedWithoutChangingRemainingOrRecording() {
        givenRemaining(0);

        assertThatThrownBy(() -> creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, TASK_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getExceptionType()).isEqualTo(ExceptionType.INSUFFICIENT_CREDIT));

        assertThat(remaining()).isZero();
        assertThat(historyCount()).isZero();
    }

    @Test
    void chargeOfLargerAmountSubtractsWholeAmount() {
        // 비용이 1보다 커져도 같은 문장이 그대로 동작해야 한다 — 비용 변경 migration만으로 비용을 바꾸는 전제(#558).
        givenRemaining(5);

        int charged = subjectCreditRepository.chargeIfSufficient(SUBJECT_ID, 2);

        assertThat(charged).isEqualTo(1);
        assertThat(remaining()).isEqualTo(3);
    }

    @Test
    void chargeOfAmountLargerThanRemainingChangesNothing() {
        // 사전 검사를 함께 통과한 동시 요청은 잔액이 비용보다 적을 수 있다 — 음수·0 클램프 없이 거절한다(#565).
        givenRemaining(1);

        int charged = subjectCreditRepository.chargeIfSufficient(SUBJECT_ID, 2);

        assertThat(charged).isZero();
        assertThat(remaining()).isEqualTo(1);
    }

    @Test
    void refundOfChargedRestoresAmountAndMarksRefundedWithReason() {
        creditService.createDefaultIfAbsent(SUBJECT_ID);
        creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, TASK_ID);

        Optional<UUID> refunded = creditService.refund(CreditCostType.TIMELINE_CREATION, TASK_ID,
                CreditRefundReason.AI_FAILED);

        assertThat(refunded).contains(SUBJECT_ID);
        assertThat(remaining()).isEqualTo(30);
        assertThat(history()).containsEntry("status", "REFUNDED").containsEntry("refund_reason", "AI_FAILED");
    }

    @Test
    void secondRefundOfSameChargeKeepsRemaining() {
        // AI FAILED 재전송·타이머 삭제 실패 뒤 만료 알림 같은 순차 재처리가 두 번 환불하지 않는다.
        creditService.createDefaultIfAbsent(SUBJECT_ID);
        creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, TASK_ID);
        creditService.refund(CreditCostType.TIMELINE_CREATION, TASK_ID, CreditRefundReason.AI_FAILED);

        Optional<UUID> refundedAgain = creditService.refund(CreditCostType.TIMELINE_CREATION, TASK_ID,
                CreditRefundReason.TIMEOUT);

        assertThat(refundedAgain).isEmpty();
        assertThat(remaining()).isEqualTo(30);
        assertThat(history()).containsEntry("refund_reason", "AI_FAILED");
    }

    @Test
    void refundOfConsumedChargeKeepsRemaining() {
        // 결과가 저장된 생성에 늦게 온 유실 알림이 차감을 되돌리지 않는다.
        creditService.createDefaultIfAbsent(SUBJECT_ID);
        creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, TASK_ID);
        creditService.consume(CreditCostType.TIMELINE_CREATION, TASK_ID);

        Optional<UUID> refunded = creditService.refund(CreditCostType.TIMELINE_CREATION, TASK_ID,
                CreditRefundReason.TIMEOUT);

        assertThat(refunded).isEmpty();
        assertThat(remaining()).isEqualTo(30 - timelineCreationCost());
        assertThat(history()).containsEntry("status", "CONSUMED");
    }

    @Test
    void refundWithoutRecordChangesNothing() {
        // 계정 삭제가 기록을 지운 뒤 또는 기록 도입 전에 시작한 작업 — 환불할 근거가 없다.
        givenRemaining(7);

        Optional<UUID> refunded = creditService.refund(CreditCostType.TIMELINE_CREATION, TASK_ID,
                CreditRefundReason.TIMEOUT);

        assertThat(refunded).isEmpty();
        assertThat(remaining()).isEqualTo(7);
    }

    @Test
    void consumeMarksChargedRecordConsumedWithoutChangingRemaining() {
        creditService.createDefaultIfAbsent(SUBJECT_ID);
        creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, TASK_ID);

        creditService.consume(CreditCostType.TIMELINE_CREATION, TASK_ID);

        assertThat(remaining()).isEqualTo(30 - timelineCreationCost());
        assertThat(history()).containsEntry("status", "CONSUMED").containsEntry("refund_reason", null);
    }

    @Test
    void consumeWithoutRecordProceedsWithoutError() {
        // 기록 도입(V10) 전에 시작한 작업의 결과 저장 — 차감 없이 저장된다(rolling 배포 수용 항목).
        givenRemaining(7);

        creditService.consume(CreditCostType.TIMELINE_CREATION, TASK_ID);

        assertThat(remaining()).isEqualTo(7);
        assertThat(historyCount()).isZero();
    }

    @Test
    void createDefaultAgainDoesNotResetSpentCredits() {
        // 가입 재실행·rollout backfill 재실행이 이미 쓴 크레딧을 기본값으로 되돌리면 무한 충전이 된다.
        givenRemaining(12);

        creditService.createDefaultIfAbsent(SUBJECT_ID);

        assertThat(remaining()).isEqualTo(12);
    }

    @Test
    void negativeRemainingIsRejectedByCheckConstraint() {
        creditService.createDefaultIfAbsent(SUBJECT_ID);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE subject_credits SET remaining = -1 WHERE subject_id = ?", SUBJECT_ID.toString()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_subject_credits_remaining_non_negative");
    }

    /** 차감순(id 증가)으로 결말 상태를 직접 넣는다 — 창 조회는 공유 DB 전체에서 최신 행부터 보므로 이 행들이 가장 최신이다. */
    private void givenHistories(String... statusAndReasons) {
        for (int i = 0; i < statusAndReasons.length; i++) {
            String[] parts = statusAndReasons[i].split(":");
            jdbcTemplate.update("INSERT INTO credit_histories (subject_id, cost_type, reference_id, amount, status, "
                            + "refund_reason, created_at, updated_at) "
                            + "VALUES (?, 'TIMELINE_CREATION', ?, 1, ?, ?, NOW(6), NOW(6))",
                    SUBJECT_ID.toString(), "00000000-0000-7000-8000-0000009481%02d".formatted(i),
                    parts[0], parts.length > 1 ? parts[1] : null);
        }
    }

    @Test
    void settledStatusesSkipInProgressAndOtherRefundReasonsAndKeepLatestOnly() {
        givenHistories("CONSUMED", "CONSUMED", "REFUNDED:AI_FAILED", "REFUNDED:TASK_SAVE_FAILED", "CONSUMED",
                "REFUNDED:TIMEOUT", "REFUNDED:DISPATCH_REJECTED", "CHARGED");

        List<CreditHistoryStatus> statuses = creditHistoryRepository.findSettledStatuses(
                CreditCostType.TIMELINE_CREATION, CreditHistoryStatus.CONSUMED, CreditHistoryStatus.REFUNDED,
                EnumSet.of(CreditRefundReason.AI_FAILED, CreditRefundReason.TIMEOUT,
                        CreditRefundReason.DISPATCH_REJECTED),
                Limit.of(5));

        // 최신순: DISPATCH_REJECTED, TIMEOUT, CONSUMED, (TASK_SAVE_FAILED 건너뜀) AI_FAILED, CONSUMED — 가장 오래된
        // CONSUMED와 CHARGED는 빠진다.
        assertThat(statuses).containsExactly(CreditHistoryStatus.REFUNDED, CreditHistoryStatus.REFUNDED,
                CreditHistoryStatus.CONSUMED, CreditHistoryStatus.REFUNDED, CreditHistoryStatus.CONSUMED);
    }

    @Test
    void deleteRemovesCreditRowAndHistoriesSoMappingCanBeDeleted() {
        creditService.createDefaultIfAbsent(SUBJECT_ID);
        creditService.charge(SUBJECT_ID, CreditCostType.TIMELINE_CREATION, TASK_ID);

        creditService.delete(SUBJECT_ID);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subject_credits WHERE subject_id = ?",
                Integer.class, SUBJECT_ID.toString())).isZero();
        assertThat(historyCount()).isZero();
        assertThat(jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?",
                SUBJECT_ID.toString())).isEqualTo(1);
    }
}
