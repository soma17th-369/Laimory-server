package com.laimory.server.credit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.credit.CreditCostType;
import com.laimory.server.credit.service.CreditService;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.testsupport.TestSubjects;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * subject_credits 실 MySQL 왕복 검증(#548) — 조건부 차감이 0에서 멈추는 것, 가입 재실행이 잔액을 되돌리지
 * 않는 것, 음수 CHECK와 삭제를 실제 제약 위에서 확인한다. credit_costs(#558)는 migration seed가 모든 비용 종류를
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

    @Autowired
    private CreditService creditService;

    @Autowired
    private SubjectCreditRepository subjectCreditRepository;

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

    /** 기대 차감액은 seed된 비용 행 기준이다 — 공유 DB의 비용 행은 테스트가 바꾸지 않는다(#558). */
    private int timelineCreationCost() {
        return jdbcTemplate.queryForObject("SELECT cost FROM credit_costs WHERE type = 'TIMELINE_CREATION'",
                Integer.class);
    }

    @Test
    void deductDecreasesRemainingByTimelineCreationCost() {
        creditService.createDefaultIfAbsent(SUBJECT_ID);

        creditService.deduct(SUBJECT_ID, CreditCostType.TIMELINE_CREATION);

        assertThat(remaining()).isEqualTo(60 - timelineCreationCost());
    }

    @Test
    void everyCreditCostTypeHasSeededCostRow() {
        // 종류를 추가하면서 migration seed를 빠뜨리면 그 기능의 사전 검사·차감이 500이 된다 — 배포 전에 잡는다.
        List<String> seededTypes = jdbcTemplate.queryForList("SELECT type FROM credit_costs", String.class);

        assertThat(seededTypes).containsAll(
                Arrays.stream(CreditCostType.values()).map(Enum::name).toList());
    }

    @Test
    void deductAtZeroKeepsZeroWithoutError() {
        givenRemaining(0);

        creditService.deduct(SUBJECT_ID, CreditCostType.TIMELINE_CREATION);

        assertThat(remaining()).isZero();
    }

    @Test
    void deductOfLargerAmountSubtractsWholeAmount() {
        // 비용이 1보다 커져도 같은 문장이 그대로 동작해야 한다 — 비용 변경 migration만으로 비용을 바꾸는 전제(#558).
        givenRemaining(5);

        subjectCreditRepository.deduct(SUBJECT_ID, 2);

        assertThat(remaining()).isEqualTo(3);
    }

    @Test
    void deductOfAmountLargerThanRemainingStopsAtZero() {
        // 사전 검사를 함께 통과한 동시 생성은 잔액이 비용보다 적을 수 있다 — 음수 대신 0에서 멈추고 결과는 저장된다.
        givenRemaining(1);

        subjectCreditRepository.deduct(SUBJECT_ID, 2);

        assertThat(remaining()).isZero();
    }

    @Test
    void createDefaultAgainDoesNotResetSpentCredits() {
        // 가입 재실행·rollout backfill 재실행이 이미 쓴 크레딧을 60으로 되돌리면 무한 충전이 된다.
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

    @Test
    void deleteRemovesCreditRowSoMappingCanBeDeleted() {
        creditService.createDefaultIfAbsent(SUBJECT_ID);

        creditService.delete(SUBJECT_ID);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subject_credits WHERE subject_id = ?",
                Integer.class, SUBJECT_ID.toString())).isZero();
        assertThat(jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?",
                SUBJECT_ID.toString())).isEqualTo(1);
    }
}
