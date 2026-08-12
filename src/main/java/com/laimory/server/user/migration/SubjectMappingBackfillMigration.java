package com.laimory.server.user.migration;

import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code users} 전 행의 subject mapping 보충 도구(#285, 계획 §5.3) — {@code backfill-mappings} 모드.
 * 콘텐츠 유무와 무관하게 전 사용자를 순회하며 {@link SubjectMappingService#createIfAbsent(long)}로
 * 누락 mapping만 멱등 생성한다(#282 이후 신규 사용자는 provisioning transaction에서 이미 가진다).
 *
 * <p>종료 시 {@code users} 수와 {@code user_subject_links} 수가 정확히 일치하는지 검증하고,
 * 불일치(누락·orphan mapping)는 fail-closed 중단이다. 로그·예외에 raw userId/HMAC/subject를 절대
 * 남기지 않는다 — 건수만 보고한다.
 */
class SubjectMappingBackfillMigration {

    private final UserRepository userRepository;
    private final SubjectMappingService subjectMappingService;
    private final JdbcTemplate jdbcTemplate;

    SubjectMappingBackfillMigration(UserRepository userRepository,
                                    SubjectMappingService subjectMappingService,
                                    JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.subjectMappingService = subjectMappingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    Result execute() {
        List<Long> userIds = userRepository.findAllUserIds();
        long created = 0;
        long alreadyPresent = 0;
        for (Long userId : userIds) {
            if (subjectMappingService.createIfAbsent(userId)) {
                created++;
            } else {
                alreadyPresent++;
            }
        }
        // mapping 1:1 검증 — users 수와 mapping 수가 다르면(누락 또는 orphan mapping) fail-closed.
        long userCount = countRows("users");
        long mappingCount = countRows("user_subject_links");
        if (userCount != mappingCount) {
            throw new SubjectMigrationAbortedException("mapping 수 불일치로 중단: users=" + userCount
                    + " mappings=" + mappingCount
                    + " mappingsCreated=" + created
                    + " mappingsAlreadyPresent=" + alreadyPresent);
        }
        return new Result(userIds.size(), created, alreadyPresent, userCount, mappingCount);
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    /** 건수 전용 실행 결과 — 식별자 값 없음. */
    record Result(long usersProcessed, long mappingsCreated, long mappingsAlreadyPresent,
                  long userCount, long mappingCount) {
    }
}
