package com.laimory.server.user.migration;

import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 콘텐츠 owner 컬럼의 subject backfill 도구(#285, 계획 §5.3~§5.4) — {@code backfill-owners} 모드.
 * {@code users} 전 행의 subject를 해석한 뒤 한 transaction에서 ① {@code daily_records}·
 * {@code timeline_draft_source_items}·{@code push_registrations}의 <b>NULL인 행만</b> 각 행의
 * user_id → mapping subject 16바이트로 채우고(멱등 — 이미 채워진 행 불변), ② {@code user_memories}를
 * {@code user_memory_documents}(subject PK)로 upsert 복사한다(memory·감사 컬럼 보존).
 *
 * <p>subject 컬럼·새 테이블은 엔티티에 매핑하지 않으므로(#283 몫) native SQL만 사용한다. 종료 시
 * {@link #verify()}로 세 테이블의 subject_id NULL 잔여·legacy user_id 대비 cross-owner와
 * user_memories↔documents의 subject·JSON·감사 컬럼 불일치를 검증하며, 불일치는 fail-closed
 * 중단이다. {@code verify-owners} 모드는 {@link #verify()}만 다시 수행한다(cutoff 이후 delta 재검증).
 *
 * <p>로그·예외에 raw userId/HMAC/subject/JSON 값을 절대 남기지 않는다 — 건수만 보고한다.
 */
class SubjectOwnerBackfillMigration {

    /**
     * user_memories → user_memory_documents row 복사 upsert. subject PK는 Java에서 해석한 16바이트
     * 파라미터고(HMAC mapping은 SQL로 join 불가), memory·감사 컬럼은 원본 행 값을 그대로 보존한다.
     * 재실행 시 원본과 같은 값으로 덮어써 멱등이다(MySQL 8.0.19+ derived table alias 참조 구문).
     *
     * <p>영향 행 수는 Connector/J 기본 CLIENT_FOUND_ROWS 의미다 — 동일 값 재실행도 행당 1로 집계된다
     * (0 아님). {@code memoryDocumentsUpserted}는 "변경된 행"이 아니라 "touch한 행" 수다.
     */
    private static final String COPY_MEMORY_DOCUMENT_SQL = """
            INSERT INTO user_memory_documents (subject_id, memory, created_at, updated_at, modified_by)
            SELECT ?, s.memory, s.created_at, s.updated_at, s.modified_by
              FROM (SELECT memory, created_at, updated_at, modified_by
                      FROM user_memories WHERE user_id = ?) AS s
            ON DUPLICATE KEY UPDATE memory = s.memory, created_at = s.created_at,
                                    updated_at = s.updated_at, modified_by = s.modified_by
            """;

    private final UserRepository userRepository;
    private final SubjectMappingService subjectMappingService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    SubjectOwnerBackfillMigration(UserRepository userRepository,
                                  SubjectMappingService subjectMappingService,
                                  JdbcTemplate jdbcTemplate,
                                  PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.subjectMappingService = subjectMappingService;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    Result execute() {
        // mapping 누락 사용자는 getRequired가 fail-closed 예외로 중단시킨다(backfill-mappings 선행 필수).
        Map<Long, byte[]> subjectBytesByUserId = resolveSubjectBytesByUserId();

        // 전 write를 한 transaction으로 묶는다 — 중단 시 부분 backfill이 남지 않는다(#284 rewrite 선례).
        WriteCounts counts = transactionTemplate.execute(status -> {
            long dailyRecords = 0;
            long stagingItems = 0;
            long pushRegistrations = 0;
            long memoryDocumentsUpserted = 0;
            for (Map.Entry<Long, byte[]> entry : subjectBytesByUserId.entrySet()) {
                long userId = entry.getKey();
                byte[] subject = entry.getValue();
                dailyRecords += jdbcTemplate.update(
                        "UPDATE daily_records SET subject_id = ? "
                                + "WHERE user_id = ? AND subject_id IS NULL",
                        subject, userId);
                stagingItems += jdbcTemplate.update(
                        "UPDATE timeline_draft_source_items SET subject_id = ? "
                                + "WHERE user_id = ? AND subject_id IS NULL",
                        subject, userId);
                pushRegistrations += jdbcTemplate.update(
                        "UPDATE push_registrations SET subject_id = ? "
                                + "WHERE user_id = ? AND subject_id IS NULL",
                        subject, userId);
                memoryDocumentsUpserted += jdbcTemplate.update(
                        COPY_MEMORY_DOCUMENT_SQL, subject, userId);
            }
            return new WriteCounts(dailyRecords, stagingItems, pushRegistrations,
                    memoryDocumentsUpserted);
        });

        Verification verification = verify();
        return new Result(subjectBytesByUserId.size(), counts.dailyRecords(), counts.stagingItems(),
                counts.pushRegistrations(), counts.memoryDocumentsUpserted(), verification);
    }

    /**
     * backfill 종료 검증(delta 검증 겸용) — NULL owner, legacy user_id에 대한 cross-owner,
     * {@code user_memories}↔{@code user_memory_documents} subject·JSON·감사 컬럼 동등성을 모두
     * 확인한다. 행 수만 같은 잘못된 owner/document도 통과시키지 않는다.
     */
    Verification verify() {
        Map<Long, byte[]> subjectBytesByUserId = resolveSubjectBytesByUserId();
        long dailyRecordsNullSubject = queryCount(
                "SELECT COUNT(*) FROM daily_records WHERE subject_id IS NULL");
        long stagingNullSubject = queryCount(
                "SELECT COUNT(*) FROM timeline_draft_source_items WHERE subject_id IS NULL");
        long pushNullSubject = queryCount(
                "SELECT COUNT(*) FROM push_registrations WHERE subject_id IS NULL");

        long dailyRecordsOwnerMismatch = countOwnerMismatches(
                "daily_records", subjectBytesByUserId);
        long stagingOwnerMismatch = countOwnerMismatches(
                "timeline_draft_source_items", subjectBytesByUserId);
        long pushOwnerMismatch = countOwnerMismatches(
                "push_registrations", subjectBytesByUserId);

        long userMemories = queryCount("SELECT COUNT(*) FROM user_memories");
        long memoryDocuments = queryCount("SELECT COUNT(*) FROM user_memory_documents");
        long matchingMemoryDocuments = 0;
        for (Map.Entry<Long, byte[]> entry : subjectBytesByUserId.entrySet()) {
            matchingMemoryDocuments += queryCount("""
                    SELECT COUNT(*)
                      FROM user_memories m
                      JOIN user_memory_documents d ON d.subject_id = ?
                     WHERE m.user_id = ?
                       AND CAST(m.memory AS CHAR) = CAST(d.memory AS CHAR)
                       AND m.created_at = d.created_at
                       AND m.updated_at = d.updated_at
                       AND (m.modified_by <=> d.modified_by)
                    """, entry.getValue(), entry.getKey());
        }
        long memoryDocumentMismatch = Math.max(userMemories, memoryDocuments)
                - matchingMemoryDocuments;

        if (dailyRecordsNullSubject != 0 || stagingNullSubject != 0 || pushNullSubject != 0
                || dailyRecordsOwnerMismatch != 0 || stagingOwnerMismatch != 0
                || pushOwnerMismatch != 0 || memoryDocumentMismatch != 0) {
            throw new SubjectMigrationAbortedException("owner 검증 실패:"
                    + " dailyRecordsNullSubject=" + dailyRecordsNullSubject
                    + " stagingNullSubject=" + stagingNullSubject
                    + " pushNullSubject=" + pushNullSubject
                    + " dailyRecordsOwnerMismatch=" + dailyRecordsOwnerMismatch
                    + " stagingOwnerMismatch=" + stagingOwnerMismatch
                    + " pushOwnerMismatch=" + pushOwnerMismatch
                    + " userMemories=" + userMemories
                    + " memoryDocuments=" + memoryDocuments
                    + " memoryDocumentMismatch=" + memoryDocumentMismatch);
        }
        return new Verification(dailyRecordsNullSubject, stagingNullSubject, pushNullSubject,
                dailyRecordsOwnerMismatch, stagingOwnerMismatch, pushOwnerMismatch,
                userMemories, memoryDocuments, memoryDocumentMismatch);
    }

    private Map<Long, byte[]> resolveSubjectBytesByUserId() {
        List<Long> userIds = userRepository.findAllUserIds();
        Map<Long, byte[]> result = new LinkedHashMap<>();
        for (Long userId : userIds) {
            result.put(userId, subjectMappingService.getRequired(userId).bytes());
        }
        return result;
    }

    /** legacy user_id가 있는 전체 행 중 mapping subject와 정확히 일치하지 않는 행 수. */
    private long countOwnerMismatches(String table, Map<Long, byte[]> subjectBytesByUserId) {
        long legacyOwnedRows = queryCount(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id IS NOT NULL");
        long matchingRows = 0;
        for (Map.Entry<Long, byte[]> entry : subjectBytesByUserId.entrySet()) {
            matchingRows += queryCount(
                    "SELECT COUNT(*) FROM " + table + " WHERE user_id = ? AND subject_id = ?",
                    entry.getKey(), entry.getValue());
        }
        return legacyOwnedRows - matchingRows;
    }

    private long queryCount(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private record WriteCounts(long dailyRecords, long stagingItems, long pushRegistrations,
                               long memoryDocumentsUpserted) {
    }

    /** 건수 전용 검증 결과 — 식별자 값 없음. */
    record Verification(long dailyRecordsNullSubject, long stagingNullSubject, long pushNullSubject,
                        long dailyRecordsOwnerMismatch, long stagingOwnerMismatch,
                        long pushOwnerMismatch, long userMemories, long memoryDocuments,
                        long memoryDocumentMismatch) {
    }

    /** 건수 전용 실행 결과 — 식별자 값 없음. */
    record Result(long usersProcessed, long dailyRecordsBackfilled, long stagingItemsBackfilled,
                  long pushRegistrationsBackfilled, long memoryDocumentsUpserted,
                  Verification verification) {
    }
}
