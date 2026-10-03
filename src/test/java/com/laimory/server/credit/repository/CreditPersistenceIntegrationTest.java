package com.laimory.server.credit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.credit.service.CreditService;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.testsupport.TestSubjects;
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
 * 않는 것, 음수 CHECK와 삭제를 실제 제약 위에서 확인한다. {@code ddl-auto=validate}라 컨텍스트 기동 자체가
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

    @Test
    void deductOneDecreasesRemainingByOne() {
        creditService.createDefaultIfAbsent(SUBJECT_ID);

        creditService.deductOne(SUBJECT_ID);

        assertThat(remaining()).isEqualTo(59);
    }

    @Test
    void deductOneAtZeroKeepsZeroWithoutError() {
        givenRemaining(0);

        creditService.deductOne(SUBJECT_ID);

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
