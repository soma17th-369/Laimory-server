package com.laimory.server.user.migration;

import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 콘텐츠 owner 컬럼의 subject backfill 도구(#285, 계획 §5.3~§5.4) — {@code backfill-owners} 모드.
 * {@code users} 전 행의 subject를 해석한 뒤 한 transaction에서 ① {@code daily_records}·
 * {@code timeline_draft_source_items}·{@code push_registrations}의 <b>NULL인 행만</b> 각 행의
 * user_id → mapping subject UUID 문자열로 채운다(멱등 — 이미 채워진 행 불변).
 *
 * <p>migration은 legacy user_id를 읽어야 하므로 native SQL을 사용한다. 종료 시
 * {@link #verify()}로 세 테이블의 subject_id NULL 잔여·legacy user_id 대비 cross-owner를 검증하며, 불일치는 fail-closed
 * 중단이다. {@code verify-owners} 모드는 {@link #verify()}만 다시 수행한다(cutoff 이후 delta 재검증).
 *
 * <p>로그·예외에 raw userId/HMAC/subject/JSON 값을 절대 남기지 않는다 — 건수만 보고한다.
 */
class SubjectOwnerBackfillMigration {

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
        Map<Long, String> subjectIdsByUserId = resolveSubjectIdsByUserId();

        // 전 write를 한 transaction으로 묶는다 — 중단 시 부분 backfill이 남지 않는다(#284 rewrite 선례).
        WriteCounts counts = transactionTemplate.execute(status -> {
            long dailyRecords = 0;
            long stagingItems = 0;
            long pushRegistrations = 0;
            for (Map.Entry<Long, String> entry : subjectIdsByUserId.entrySet()) {
                long userId = entry.getKey();
                String subject = entry.getValue();
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
            }
            return new WriteCounts(dailyRecords, stagingItems, pushRegistrations);
        });

        Verification verification = verify();
        return new Result(subjectIdsByUserId.size(), counts.dailyRecords(), counts.stagingItems(),
                counts.pushRegistrations(), verification);
    }

    /**
     * backfill 종료 검증(delta 검증 겸용) — NULL owner, legacy user_id에 대한 cross-owner,
     * cross-owner 일치를 확인한다. 행 수만 같은 잘못된 owner도 통과시키지 않는다.
     */
    Verification verify() {
        Map<Long, String> subjectIdsByUserId = resolveSubjectIdsByUserId();
        long dailyRecordsNullSubject = queryCount(
                "SELECT COUNT(*) FROM daily_records WHERE subject_id IS NULL");
        long stagingNullSubject = queryCount(
                "SELECT COUNT(*) FROM timeline_draft_source_items WHERE subject_id IS NULL");
        long pushNullSubject = queryCount(
                "SELECT COUNT(*) FROM push_registrations WHERE subject_id IS NULL");

        long dailyRecordsOwnerMismatch = countOwnerMismatches(
                "daily_records", subjectIdsByUserId);
        long stagingOwnerMismatch = countOwnerMismatches(
                "timeline_draft_source_items", subjectIdsByUserId);
        long pushOwnerMismatch = countOwnerMismatches(
                "push_registrations", subjectIdsByUserId);

        if (dailyRecordsNullSubject != 0 || stagingNullSubject != 0 || pushNullSubject != 0
                || dailyRecordsOwnerMismatch != 0 || stagingOwnerMismatch != 0
                || pushOwnerMismatch != 0) {
            throw new SubjectMigrationAbortedException("owner 검증 실패:"
                    + " dailyRecordsNullSubject=" + dailyRecordsNullSubject
                    + " stagingNullSubject=" + stagingNullSubject
                    + " pushNullSubject=" + pushNullSubject
                    + " dailyRecordsOwnerMismatch=" + dailyRecordsOwnerMismatch
                    + " stagingOwnerMismatch=" + stagingOwnerMismatch
                    + " pushOwnerMismatch=" + pushOwnerMismatch);
        }
        return new Verification(dailyRecordsNullSubject, stagingNullSubject, pushNullSubject,
                dailyRecordsOwnerMismatch, stagingOwnerMismatch, pushOwnerMismatch);
    }

    private Map<Long, String> resolveSubjectIdsByUserId() {
        List<Long> userIds = userRepository.findAllUserIds();
        Map<Long, String> result = new LinkedHashMap<>();
        for (Long userId : userIds) {
            result.put(userId, subjectMappingService.getRequired(userId).toString());
        }
        return result;
    }

    /** legacy user_id가 있는 전체 행 중 mapping subject와 정확히 일치하지 않는 행 수. */
    private long countOwnerMismatches(String table, Map<Long, String> subjectIdsByUserId) {
        long legacyOwnedRows = queryCount(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id IS NOT NULL");
        long matchingRows = 0;
        for (Map.Entry<Long, String> entry : subjectIdsByUserId.entrySet()) {
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

    private record WriteCounts(long dailyRecords, long stagingItems, long pushRegistrations) {
    }

    /** 건수 전용 검증 결과 — 식별자 값 없음. */
    record Verification(long dailyRecordsNullSubject, long stagingNullSubject, long pushNullSubject,
                        long dailyRecordsOwnerMismatch, long stagingOwnerMismatch,
                        long pushOwnerMismatch) {
    }

    /** 건수 전용 실행 결과 — 식별자 값 없음. */
    record Result(long usersProcessed, long dailyRecordsBackfilled, long stagingItemsBackfilled,
                  long pushRegistrationsBackfilled, Verification verification) {
    }
}
