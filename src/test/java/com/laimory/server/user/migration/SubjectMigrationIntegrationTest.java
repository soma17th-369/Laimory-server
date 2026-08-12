package com.laimory.server.user.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.user.NewUserProvisioner;
import com.laimory.server.user.Provider;
import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.User;
import com.laimory.server.user.UserRepository;
import com.laimory.server.user.UserSubjectLinkRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * subject backfill 도구의 실 MySQL 왕복 검증(#285) — mapping 멱등 생성과 1:1 검증, NULL owner
 * 컬럼 backfill의 멱등 재실행, 검증 실패의 fail-closed
 * 중단(건수 전용 메시지).
 *
 * <p>executor는 property 게이트 밖에서 직접 조립한다 — {@code app.subject.migration.mode}를 켠
 * 컨텍스트는 기동 시 runner가 실행되므로 테스트에서 켜지 않는다. backfill·검증은 전체 테이블을
 * 스캔하므로 이 테스트는 다른 테스트처럼 자신이 만든 행을 모두 정리한다는 전제를 공유한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubjectMigrationIntegrationTest {

    @Autowired
    private NewUserProvisioner newUserProvisioner;
    @Autowired
    private SubjectMappingService subjectMappingService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    // 정리 전용 — repository·deriver 직접 접근은 테스트 한정 예외다(arch rule은 main 코드만 검사).
    @Autowired
    private SubjectLookupKeyDeriver subjectLookupKeyDeriver;
    @Autowired
    private UserSubjectLinkRepository userSubjectLinkRepository;

    private SubjectMappingBackfillMigration mappingBackfill;
    private SubjectOwnerBackfillMigration ownerBackfill;

    private final List<Long> createdUserIds = new ArrayList<>();

    /**
     * #283 schema.sql은 activation 후 NOT NULL 계약이다. 이 클래스는 cutover 이미지가 final DDL 전
     * additive schema의 NULL legacy owner를 실제로 backfill할 수 있는지를 검증하므로, 클래스 경계에서만
     * subject_id를 nullable로 열고 종료 시 activation 계약으로 복구한다. 통합 테스트는 기본 순차 실행이고
     * 이 migration은 전체 테이블 스캔이라 원래부터 독점적 테스 상태를 전제한다.
     */
    @BeforeAll
    void openAdditiveOwnerSchema() {
        jdbcTemplate.execute("ALTER TABLE daily_records MODIFY COLUMN subject_id "
                + "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL");
        jdbcTemplate.execute("ALTER TABLE timeline_draft_source_items MODIFY COLUMN subject_id "
                + "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL");
        jdbcTemplate.execute("ALTER TABLE push_registrations MODIFY COLUMN subject_id "
                + "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL");
    }

    @AfterAll
    void restoreActivationOwnerSchema() {
        jdbcTemplate.execute("ALTER TABLE daily_records MODIFY COLUMN subject_id "
                + "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
        jdbcTemplate.execute("ALTER TABLE timeline_draft_source_items MODIFY COLUMN subject_id "
                + "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
        jdbcTemplate.execute("ALTER TABLE push_registrations MODIFY COLUMN subject_id "
                + "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
    }

    @BeforeEach
    void setUp() {
        mappingBackfill = new SubjectMappingBackfillMigration(userRepository,
                subjectMappingService, jdbcTemplate);
        ownerBackfill = new SubjectOwnerBackfillMigration(userRepository, subjectMappingService,
                jdbcTemplate, transactionManager);
    }

    @AfterEach
    void cleanUp() {
        for (Long userId : createdUserIds) {
            // FK RESTRICT 순서: subject를 참조하는 콘텐츠 행 → mapping → user.
            jdbcTemplate.update("DELETE FROM daily_records WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM timeline_draft_source_items WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM push_registrations WHERE user_id = ?", userId);
            byte[] lookupKey = subjectLookupKeyDeriver.deriveCurrent(userId);
            userSubjectLinkRepository.findById(lookupKey).ifPresent(link -> {
                userSubjectLinkRepository.deleteById(lookupKey);
            });
            userRepository.deleteById(userId);
        }
        createdUserIds.clear();
    }

    /** mapping 없는 user 직접 insert — provisioner는 항상 mapping을 함께 만들므로 repo로 우회한다. */
    private long saveUserWithoutMapping() {
        User user = userRepository.saveAndFlush(
                User.of(Provider.GOOGLE, "subject-mig-" + UUID.randomUUID(), null, null));
        createdUserIds.add(user.getUserId());
        return user.getUserId();
    }

    private long provisionUserWithMapping() {
        long userId = newUserProvisioner
                .provision(Provider.GOOGLE, "subject-mig-" + UUID.randomUUID(), null, null)
                .getUserId();
        createdUserIds.add(userId);
        return userId;
    }

    private long insertDailyRecord(long userId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO daily_records (user_id, record_date, record_at, "
                        + "record_timezone, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'Asia/Seoul', 'DRAFT', ?, ?)",
                userId, LocalDate.now(), now, now, now);
        return jdbcTemplate.queryForObject("SELECT daily_record_id FROM daily_records "
                + "WHERE user_id = ?", Long.class, userId);
    }

    private long insertStagingItem(long userId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO timeline_draft_source_items (task_id, user_id, item_type, "
                        + "raw_id, payload, created_at, updated_at) "
                        + "VALUES (?, ?, 'PHOTO', ?, '{}', ?, ?)",
                UUID.randomUUID().toString(), userId, UUID.randomUUID().toString(), now, now);
        return jdbcTemplate.queryForObject("SELECT timeline_draft_source_item_id "
                + "FROM timeline_draft_source_items WHERE user_id = ?", Long.class, userId);
    }

    private long insertPushRegistration(long userId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("INSERT INTO push_registrations (user_id, firebase_installation_id, "
                        + "last_registered_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                userId, "fid-" + UUID.randomUUID(), now, now, now);
        return jdbcTemplate.queryForObject("SELECT push_registration_id FROM push_registrations "
                + "WHERE user_id = ?", Long.class, userId);
    }

    private String subjectIdOf(long userId) {
        return subjectMappingService.getRequired(userId).toString();
    }

    @Test
    void backfillMappings_createsMissingMappingAndSecondRunIsIdempotent() {
        long userId = saveUserWithoutMapping();

        SubjectMappingBackfillMigration.Result firstRun = mappingBackfill.execute();

        assertThat(firstRun.mappingsCreated()).isGreaterThanOrEqualTo(1);
        assertThat(firstRun.userCount()).isEqualTo(firstRun.mappingCount()); // 1:1 검증 통과
        assertThatCode(() -> subjectMappingService.getRequired(userId)).doesNotThrowAnyException();

        SubjectMappingBackfillMigration.Result secondRun = mappingBackfill.execute();

        assertThat(secondRun.mappingsCreated()).isZero(); // 멱등 재실행 — 새 mapping 없음
        assertThat(secondRun.mappingsAlreadyPresent())
                .isEqualTo(firstRun.mappingsCreated() + firstRun.mappingsAlreadyPresent());
    }

    @Test
    void backfillOwners_fillsNullOwners_idempotently() {
        long userId = provisionUserWithMapping();
        long dailyRecordId = insertDailyRecord(userId);
        long stagingItemId = insertStagingItem(userId);
        long pushRegistrationId = insertPushRegistration(userId);
        String subjectId = subjectIdOf(userId);

        SubjectOwnerBackfillMigration.Result firstRun = ownerBackfill.execute();

        assertThat(firstRun.dailyRecordsBackfilled()).isEqualTo(1);
        assertThat(firstRun.stagingItemsBackfilled()).isEqualTo(1);
        assertThat(firstRun.pushRegistrationsBackfilled()).isEqualTo(1);
        assertThat(firstRun.verification().dailyRecordsNullSubject()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT subject_id FROM daily_records "
                + "WHERE daily_record_id = ?", String.class, dailyRecordId)).isEqualTo(subjectId);
        assertThat(jdbcTemplate.queryForObject("SELECT subject_id FROM timeline_draft_source_items "
                + "WHERE timeline_draft_source_item_id = ?", String.class, stagingItemId))
                .isEqualTo(subjectId);
        assertThat(jdbcTemplate.queryForObject("SELECT subject_id FROM push_registrations "
                + "WHERE push_registration_id = ?", String.class, pushRegistrationId))
                .isEqualTo(subjectId);

        SubjectOwnerBackfillMigration.Result secondRun = ownerBackfill.execute();

        // 멱등 재실행 — NULL인 행이 없어 owner UPDATE 영향 행이 없다.
        assertThat(secondRun.dailyRecordsBackfilled()).isZero();
        assertThat(secondRun.stagingItemsBackfilled()).isZero();
        assertThat(secondRun.pushRegistrationsBackfilled()).isZero();
    }

    @Test
    void verifyOwners_nullSubjectRemains_abortsWithCountOnlyMessage() {
        long userId = provisionUserWithMapping();
        insertDailyRecord(userId);

        assertThatThrownBy(ownerBackfill::verify)
                .isInstanceOf(SubjectMigrationAbortedException.class)
                .hasMessageContaining("dailyRecordsNullSubject=1");
    }

    @Test
    void verifyOwners_nonNullCrossOwner_abortsFailClosed() {
        long ownerUserId = provisionUserWithMapping();
        long otherUserId = provisionUserWithMapping();
        long dailyRecordId = insertDailyRecord(ownerUserId);
        long stagingItemId = insertStagingItem(ownerUserId);
        long pushRegistrationId = insertPushRegistration(ownerUserId);
        ownerBackfill.execute();

        String otherSubject = subjectIdOf(otherUserId);
        jdbcTemplate.update("UPDATE daily_records SET subject_id = ? WHERE daily_record_id = ?",
                otherSubject, dailyRecordId);
        jdbcTemplate.update("UPDATE timeline_draft_source_items SET subject_id = ? "
                + "WHERE timeline_draft_source_item_id = ?", otherSubject, stagingItemId);
        jdbcTemplate.update("UPDATE push_registrations SET subject_id = ? "
                + "WHERE push_registration_id = ?", otherSubject, pushRegistrationId);

        assertThatThrownBy(ownerBackfill::verify)
                .isInstanceOf(SubjectMigrationAbortedException.class)
                .hasMessageContaining("dailyRecordsOwnerMismatch=1")
                .hasMessageContaining("stagingOwnerMismatch=1")
                .hasMessageContaining("pushOwnerMismatch=1");
    }

}
