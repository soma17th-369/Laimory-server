package com.laimory.server.user.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.user.NewUserProvisioner;
import com.laimory.server.user.Provider;
import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.User;
import com.laimory.server.user.UserMemoryRepository;
import com.laimory.server.user.UserRepository;
import com.laimory.server.user.UserSubjectLinkRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * subject backfill 도구의 실 MySQL 왕복 검증(#285) — mapping 멱등 생성과 1:1 검증, NULL owner
 * 컬럼 backfill·user_memories→user_memory_documents 복사의 멱등 재실행, 검증 실패의 fail-closed
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
class SubjectMigrationIntegrationTest {

    @Autowired
    private NewUserProvisioner newUserProvisioner;
    @Autowired
    private SubjectMappingService subjectMappingService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserMemoryRepository userMemoryRepository;
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
            // FK RESTRICT 순서: subject를 참조하는 콘텐츠·문서 행 → mapping → user.
            jdbcTemplate.update("DELETE FROM daily_records WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM timeline_draft_source_items WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM push_registrations WHERE user_id = ?", userId);
            userMemoryRepository.deleteByUserId(userId);
            byte[] lookupKey = subjectLookupKeyDeriver.deriveCurrent(userId);
            userSubjectLinkRepository.findById(lookupKey).ifPresent(link -> {
                jdbcTemplate.update("DELETE FROM user_memory_documents WHERE subject_id = ?",
                        (Object) link.getSubjectId());
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

    private byte[] subjectBytesOf(long userId) {
        return subjectMappingService.getRequired(userId).bytes();
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
    void backfillOwners_fillsNullOwnersAndCopiesMemoryDocument_idempotently() {
        long userId = provisionUserWithMapping();
        long dailyRecordId = insertDailyRecord(userId);
        long stagingItemId = insertStagingItem(userId);
        long pushRegistrationId = insertPushRegistration(userId);
        userMemoryRepository.upsert(userId, "{\"summary\": \"first\"}", LocalDateTime.now());
        byte[] subjectBytes = subjectBytesOf(userId);

        SubjectOwnerBackfillMigration.Result firstRun = ownerBackfill.execute();

        assertThat(firstRun.dailyRecordsBackfilled()).isEqualTo(1);
        assertThat(firstRun.stagingItemsBackfilled()).isEqualTo(1);
        assertThat(firstRun.pushRegistrationsBackfilled()).isEqualTo(1);
        assertThat(firstRun.memoryDocumentsUpserted()).isEqualTo(1);
        assertThat(firstRun.verification().dailyRecordsNullSubject()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT subject_id FROM daily_records "
                + "WHERE daily_record_id = ?", byte[].class, dailyRecordId)).isEqualTo(subjectBytes);
        assertThat(jdbcTemplate.queryForObject("SELECT subject_id FROM timeline_draft_source_items "
                + "WHERE timeline_draft_source_item_id = ?", byte[].class, stagingItemId))
                .isEqualTo(subjectBytes);
        assertThat(jdbcTemplate.queryForObject("SELECT subject_id FROM push_registrations "
                + "WHERE push_registration_id = ?", byte[].class, pushRegistrationId))
                .isEqualTo(subjectBytes);
        // 문서 복사 — memory JSON과 감사 컬럼이 원본 행과 동일하다(MySQL 정규화 표현으로 비교).
        assertThat(jdbcTemplate.queryForObject("SELECT (SELECT CAST(memory AS CHAR) "
                        + "FROM user_memories WHERE user_id = ?) = (SELECT CAST(memory AS CHAR) "
                        + "FROM user_memory_documents WHERE subject_id = ?)", Boolean.class,
                userId, subjectBytes)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT (SELECT created_at FROM user_memories "
                        + "WHERE user_id = ?) = (SELECT created_at FROM user_memory_documents "
                        + "WHERE subject_id = ?)", Boolean.class, userId, subjectBytes)).isTrue();

        SubjectOwnerBackfillMigration.Result secondRun = ownerBackfill.execute();

        // 멱등 재실행 — NULL인 행이 없어 owner UPDATE 영향 행이 없다. 문서 upsert는 동일 값이어도
        // Connector/J 기본 CLIENT_FOUND_ROWS 의미로 touch한 행당 1로 집계된다(상태는 불변).
        assertThat(secondRun.dailyRecordsBackfilled()).isZero();
        assertThat(secondRun.stagingItemsBackfilled()).isZero();
        assertThat(secondRun.pushRegistrationsBackfilled()).isZero();
        assertThat(secondRun.memoryDocumentsUpserted()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT (SELECT CAST(memory AS CHAR) "
                        + "FROM user_memories WHERE user_id = ?) = (SELECT CAST(memory AS CHAR) "
                        + "FROM user_memory_documents WHERE subject_id = ?)", Boolean.class,
                userId, subjectBytes)).isTrue();

        // 원본 memory 갱신 뒤 재실행하면 문서가 최신 원본으로 수렴한다(upsert delta 반영).
        userMemoryRepository.upsert(userId, "{\"summary\": \"second\"}", LocalDateTime.now());
        ownerBackfill.execute();
        assertThat(jdbcTemplate.queryForObject("SELECT (SELECT CAST(memory AS CHAR) "
                        + "FROM user_memories WHERE user_id = ?) = (SELECT CAST(memory AS CHAR) "
                        + "FROM user_memory_documents WHERE subject_id = ?)", Boolean.class,
                userId, subjectBytes)).isTrue();
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
    void verifyOwners_memoryDocumentCountMismatch_abortsFailClosed() {
        long userId = provisionUserWithMapping();
        userMemoryRepository.upsert(userId, "{\"summary\": \"first\"}", LocalDateTime.now());
        ownerBackfill.execute(); // 문서 복사 + 검증 통과

        // 복사 후 원본 행이 사라지면(문서만 남음) count 불일치 — delta 검증이 fail-closed로 잡는다.
        userMemoryRepository.deleteByUserId(userId);

        assertThatThrownBy(ownerBackfill::verify)
                .isInstanceOf(SubjectMigrationAbortedException.class)
                .hasMessageContaining("userMemories=0")
                .hasMessageContaining("memoryDocuments=1");
    }

    @Test
    void verifyOwners_nonNullCrossOwner_abortsFailClosed() {
        long ownerUserId = provisionUserWithMapping();
        long otherUserId = provisionUserWithMapping();
        long dailyRecordId = insertDailyRecord(ownerUserId);
        long stagingItemId = insertStagingItem(ownerUserId);
        long pushRegistrationId = insertPushRegistration(ownerUserId);
        ownerBackfill.execute();

        byte[] otherSubject = subjectBytesOf(otherUserId);
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

    @Test
    void verifyOwners_sameCountButDifferentMemoryDocument_abortsFailClosed() {
        long userId = provisionUserWithMapping();
        userMemoryRepository.upsert(userId, "{\"summary\": \"legacy\"}", LocalDateTime.now());
        ownerBackfill.execute();

        jdbcTemplate.update("UPDATE user_memory_documents SET memory = ? WHERE subject_id = ?",
                "{\"summary\": \"different\"}", subjectBytesOf(userId));

        assertThatThrownBy(ownerBackfill::verify)
                .isInstanceOf(SubjectMigrationAbortedException.class)
                .hasMessageContaining("userMemories=1")
                .hasMessageContaining("memoryDocuments=1")
                .hasMessageContaining("memoryDocumentMismatch=1");
    }

    @Test
    void verifyOwners_memoryDocumentDiffersOnlyByLetterCase_abortsFailClosed() {
        long userId = provisionUserWithMapping();
        userMemoryRepository.upsert(userId, "{\"summary\": \"CaseSensitive\"}",
                LocalDateTime.now());
        ownerBackfill.execute();

        jdbcTemplate.update("UPDATE user_memory_documents SET memory = ? WHERE subject_id = ?",
                "{\"summary\": \"casesensitive\"}", subjectBytesOf(userId));

        assertThatThrownBy(ownerBackfill::verify)
                .isInstanceOf(SubjectMigrationAbortedException.class)
                .hasMessageContaining("memoryDocumentMismatch=1");
    }
}
