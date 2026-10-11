package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.timeline.entity.UserMemoryUpdatePending;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import com.laimory.server.user.Provider;
import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.entity.AccountErasureJob;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.repository.AccountErasureJobRepository;
import com.laimory.server.user.repository.UserRepository;
import com.laimory.server.user.repository.UserSubjectLinkRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 계정 삭제 worker ↔ 실 MySQL·Redis 왕복 검증(#302·#397).
 *
 * <p>검증하는 것은 claim 경계와 삭제 순서다.
 * <ul>
 *   <li>처리 창 — 접수일 D 기준 D+3~D+5만 claim되고, D+2는 이르고 D+6은 만료다(#397, 약관 "5일 이내").</li>
 *   <li>같은 날 재선택 방지와 다음 날 재claim, 두 인스턴스 동시 claim의 {@code SKIP LOCKED} 분배.</li>
 *   <li>탈퇴 commit 직후 User Memory 미반영 큐가 비워진다.</li>
 *   <li>{@code account_erasure_jobs}가 남은 회원 행은 지울 수 없다(FK RESTRICT).</li>
 *   <li>콘텐츠가 없는/있는 회원의 접수 → 삭제 E2E.</li>
 * </ul>
 *
 * 실행: docker compose up -d --wait 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class AccountErasureIntegrationTest {

    private static final int GRACE_DAYS = 2;
    private static final int WINDOW_DAYS = 3;
    private static final int LIMIT = 50;

    @Autowired
    private NewUserProvisioner newUserProvisioner;
    @Autowired
    private UserWithdrawalService userWithdrawalService;
    @Autowired
    private AccountErasureJobService accountErasureJobService;
    @Autowired
    private AccountErasureService accountErasureService;
    @Autowired
    private AccountErasureJobRepository accountErasureJobRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserSubjectLinkRepository userSubjectLinkRepository;
    @Autowired
    private SubjectLookupKeyDeriver subjectLookupKeyDeriver;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserMemoryUpdatePendingStore userMemoryUpdatePendingStore;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<UUID> createdSubjectIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(userId -> jdbcTemplate.update(
                "DELETE FROM account_erasure_jobs WHERE user_id = ?", userId));
        createdSubjectIds.forEach(subjectId -> {
            jdbcTemplate.update("DELETE FROM user_memories WHERE subject_id = ?", subjectId.toString());
            jdbcTemplate.update("DELETE FROM subject_credits WHERE subject_id = ?", subjectId.toString());
            jdbcTemplate.update("DELETE FROM daily_notification_preferences WHERE subject_id = ?",
                    subjectId.toString());
            jdbcTemplate.update("DELETE FROM subject_preferences WHERE subject_id = ?", subjectId.toString());
        });
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.forEach(userId ->
                userSubjectLinkRepository.deleteById(subjectLookupKeyDeriver.deriveCurrent(userId)));
        createdUserIds.clear();
        createdSubjectIds.clear();
    }

    private long provisionedUser() {
        User user = newUserProvisioner.provision(Provider.KAKAO,
                "erasure-it-" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L),
                null, "탈퇴예정");
        createdUserIds.add(user.getUserId());
        createdSubjectIds.add(subjectOf(user.getUserId()));
        return user.getUserId();
    }

    private long withdrawnUser() {
        long userId = provisionedUser();
        userWithdrawalService.withdraw("1", userId);
        return userId;
    }

    /** subject_id는 VARCHAR(36)이라 String으로 읽어 파싱한다 — UUID로 바로 받으면 바이트가 그대로 해석된다. */
    private UUID subjectOf(long userId) {
        return UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT subject_id FROM user_subject_links WHERE user_lookup_key = ?",
                String.class, (Object) subjectLookupKeyDeriver.deriveCurrent(userId)));
    }

    /** 접수 시각을 과거로 옮긴다 — 두 감사 컬럼을 함께 옮겨야 실제 접수 행과 같은 모양이 된다. */
    private void backdate(long userId, LocalDateTime createdAt) {
        jdbcTemplate.update("UPDATE account_erasure_jobs SET created_at = ?, updated_at = ? WHERE user_id = ?",
                createdAt, createdAt, userId);
    }

    private List<AccountErasureJob> claimForDelete(LocalDateTime todayStart) {
        return accountErasureJobService.claimForDelete(
                todayStart.minusDays((long) GRACE_DAYS + WINDOW_DAYS),
                todayStart.minusDays(GRACE_DAYS),
                todayStart,
                todayStart.plusHours(2),
                LIMIT);
    }

    private boolean claimed(List<AccountErasureJob> jobs, long userId) {
        return jobs.stream().anyMatch(job -> job.getUserId() == userId);
    }

    @Test
    void 유예가_지나지_않은_접수는_삭제_대상이_아니다() {
        long userId = withdrawnUser();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        backdate(userId, todayStart.minusDays(GRACE_DAYS).plusHours(1)); // D+2 — 아직 이르다

        assertThat(claimed(claimForDelete(todayStart), userId)).isFalse();
    }

    @Test
    void 처리_창_안의_접수만_claim된다() {
        long userId = withdrawnUser();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        // D+3(창의 첫날)
        backdate(userId, todayStart.minusDays(GRACE_DAYS + 1L).plusHours(3));
        assertThat(claimed(claimForDelete(todayStart), userId)).isTrue();

        // D+5(창의 마지막 날)
        backdate(userId, todayStart.minusDays((long) GRACE_DAYS + WINDOW_DAYS).plusHours(3));
        assertThat(claimed(claimForDelete(todayStart), userId)).isTrue();
    }

    @Test
    void 창을_벗어난_접수는_재시도하지_않고_만료로_집계된다() {
        long userId = withdrawnUser();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        backdate(userId, todayStart.minusDays((long) GRACE_DAYS + WINDOW_DAYS + 1).minusHours(1)); // D+6

        assertThat(claimed(claimForDelete(todayStart), userId)).isFalse();
        assertThat(accountErasureJobService.countExpired(
                todayStart.minusDays((long) GRACE_DAYS + WINDOW_DAYS))).isPositive();
    }

    @Test
    void 같은_날_두_번_claim되지_않고_다음_날_다시_잡힌다() {
        long userId = withdrawnUser();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        backdate(userId, todayStart.minusDays(GRACE_DAYS + 1L).plusHours(3));

        assertThat(claimed(claimForDelete(todayStart), userId)).isTrue();
        assertThat(claimed(claimForDelete(todayStart), userId)).isFalse(); // updated_at이 오늘로 표시됨

        LocalDateTime tomorrowStart = todayStart.plusDays(1);
        assertThat(claimed(claimForDelete(tomorrowStart), userId)).isTrue();
    }

    @Test
    void 수동_확인_대기_job은_claim_대상에서_빠진다() {
        long userId = withdrawnUser();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        backdate(userId, todayStart.minusDays(GRACE_DAYS + 1L).plusHours(3));
        accountErasureJobService.markManualReview(jobIdOf(userId));

        assertThat(claimed(claimForDelete(todayStart), userId)).isFalse();
        assertThat(accountErasureJobService.countManualReview()).isPositive();
    }

    /** 두 인스턴스가 같은 cron으로 동시에 claim해도 한 job은 한 번만 잡히고, 같은 날 다시 잡히지 않는다. */
    @Test
    void 동시_claim은_겹치지_않고_같은_날_다시_잡지_않는다() throws Exception {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Set<Long> ours = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            long userId = withdrawnUser();
            backdate(userId, todayStart.minusDays(GRACE_DAYS + 1L).plusHours(3));
            ours.add(jobIdOf(userId));
        }

        Set<Long> claimedIds = new HashSet<>();
        claimConcurrently(todayStart).forEach(batch -> addDisjoint(claimedIds, batch));
        List<AccountErasureJob> next = claimOne(todayStart);
        while (!next.isEmpty()) {
            addDisjoint(claimedIds, next);
            next = claimOne(todayStart);
        }

        assertThat(claimedIds).containsAll(ours);
        assertThat(claimForDelete(todayStart))
                .extracting(AccountErasureJob::getAccountErasureJobId)
                .doesNotContainAnyElementsOf(ours);
    }

    @Test
    void 탈퇴하면_User_Memory_미반영_큐가_비워진다() {
        long userId = provisionedUser();
        UUID subjectId = createdSubjectIds.get(createdSubjectIds.size() - 1);
        long recordId = insertSavedRecord(subjectId, "2026-01-04");
        userMemoryUpdatePendingStore.enqueue(new UserMemoryUpdatePending(subjectId, recordId), Instant.now());
        assertThat(pendingRecordIds(subjectId)).containsExactly(recordId);

        userWithdrawalService.withdraw("1", userId);

        assertThat(pendingRecordIds(subjectId)).isEmpty();
        jdbcTemplate.update("DELETE FROM daily_records WHERE subject_id = ?", subjectId.toString());
    }

    @Test
    void 삭제_작업이_남은_회원_행은_지울_수_없다() {
        long userId = withdrawnUser();

        assertThatThrownBy(() -> {
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 콘텐츠가_없는_회원은_접수에서_소거까지_끝난다() {
        long userId = withdrawnUser();
        UUID subjectId = createdSubjectIds.get(createdSubjectIds.size() - 1);
        long jobId = jobIdOf(userId);

        UUID resolved = accountErasureService.resolveTarget(userId);
        assertThat(resolved).isEqualTo(subjectId);

        accountErasureService.deleteOwnerRows(userId, resolved);
        accountErasureService.finalizeErasure(jobId, userId, resolved);

        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(accountErasureJobRepository.findById(jobId)).isEmpty();
        assertThat(countBySubject("subject_preferences", subjectId)).isZero();
        assertThat(countBySubject("daily_notification_preferences", subjectId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_subject_links WHERE subject_id = ?", Integer.class,
                subjectId.toString())).isZero();

        // 이미 소거된 뒤라 정리 대상이 없다.
        createdUserIds.clear();
        createdSubjectIds.clear();
    }

    @Test
    void 경쟁에서_진_worker의_finalization은_아무것도_바꾸지_않는다() {
        long userId = withdrawnUser();
        UUID subjectId = createdSubjectIds.get(createdSubjectIds.size() - 1);
        long jobId = jobIdOf(userId);
        accountErasureService.deleteOwnerRows(userId, subjectId);

        accountErasureService.finalizeErasure(jobId, userId, subjectId);
        // 두 번째 호출은 mapping이 이미 없어 0행 — 예외로 rollback되고 남은 행을 건드리지 않는다.
        assertThatThrownBy(() -> accountErasureService.finalizeErasure(jobId, userId, subjectId))
                .isInstanceOf(AccountErasureConflictException.class);

        createdUserIds.clear();
        createdSubjectIds.clear();
    }

    /**
     * finalization 중 어느 단계가 0행이어도 mapping·job·user가 <b>모두</b> 남아야 한다.
     * boolean 반환으로 조용히 끝내면 Spring이 rollback하지 않아 앞 단계 DELETE가 commit된다 —
     * 특히 마지막 회원 행 단계가 0행이면 job이 사라진 뒤라 아무도 그 행을 다시 건드리지 않는다.
     */
    @Test
    void job_단계가_0행이면_mapping도_함께_되돌아온다() {
        long userId = withdrawnUser();
        UUID subjectId = createdSubjectIds.get(createdSubjectIds.size() - 1);
        long jobId = jobIdOf(userId);
        // mapping 삭제가 subject FK에 막히지 않도록 owner 행을 먼저 정리한다(운영 순서와 동일).
        accountErasureService.deleteOwnerRows(userId, subjectId);
        // claim 뒤 다른 worker가 MANUAL_REVIEW로 격리해 기대 상태(PENDING)가 아니다 — job 삭제가 0행이 된다
        // (mapping 삭제는 성공한 뒤다).
        accountErasureJobService.markManualReview(jobId);

        assertThatThrownBy(() -> accountErasureService.finalizeErasure(jobId, userId, subjectId))
                .isInstanceOf(AccountErasureConflictException.class);

        assertThat(mappingCount(subjectId)).isOne();
        assertThat(accountErasureJobRepository.findById(jobId)).isPresent();
        assertThat(userRepository.findById(userId)).isPresent();
    }

    @Test
    void 회원_단계가_0행이면_mapping과_job이_모두_되돌아온다() {
        long userId = withdrawnUser();
        UUID subjectId = createdSubjectIds.get(createdSubjectIds.size() - 1);
        long jobId = jobIdOf(userId);
        accountErasureService.deleteOwnerRows(userId, subjectId);
        // 회원 상태를 되돌려 마지막 단계만 0행으로 만든다.
        jdbcTemplate.update("UPDATE users SET status = 'ACTIVE' WHERE user_id = ?", userId);

        assertThatThrownBy(() -> accountErasureService.finalizeErasure(jobId, userId, subjectId))
                .isInstanceOf(AccountErasureConflictException.class);

        assertThat(mappingCount(subjectId)).isOne();
        assertThat(accountErasureJobRepository.findById(jobId)).isPresent();
        assertThat(userRepository.findById(userId)).isPresent();
    }

    @Test
    void mapping_단계가_0행이면_job과_회원_행이_남는다() {
        long userId = withdrawnUser();
        long jobId = jobIdOf(userId);

        assertThatThrownBy(() -> accountErasureService.finalizeErasure(jobId, userId, UUID.randomUUID()))
                .isInstanceOf(AccountErasureConflictException.class);

        assertThat(accountErasureJobRepository.findById(jobId)).isPresent();
        assertThat(userRepository.findById(userId)).isPresent();
    }

    private int mappingCount(UUID subjectId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_subject_links WHERE subject_id = ?", Integer.class,
                subjectId.toString());
    }

    /**
     * 콘텐츠가 있는 회원도 graph 삭제 뒤 mapping까지 내려간다 — subject FK {@code RESTRICT}가 걸리면
     * finalization이 통째로 rollback되므로, 이 테스트가 통과한다는 것은 콘텐츠가 실제로 0이 됐다는 뜻이다.
     */
    @Test
    void 콘텐츠가_있는_회원도_graph_삭제_뒤_소거된다() {
        long userId = withdrawnUser();
        UUID subjectId = createdSubjectIds.get(createdSubjectIds.size() - 1);
        long jobId = jobIdOf(userId);

        jdbcTemplate.update("INSERT INTO daily_records (subject_id, record_date, record_at, "
                        + "record_timezone, status, created_at, updated_at) "
                        + "VALUES (?, '2026-01-02', '2026-01-02 10:00:00', 'Asia/Seoul', 'SAVED', now(6), now(6))",
                subjectId.toString());
        Long recordId = jdbcTemplate.queryForObject(
                "SELECT daily_record_id FROM daily_records WHERE subject_id = ?", Long.class,
                subjectId.toString());
        jdbcTemplate.update("INSERT INTO timeline_events (daily_record_id, start_at, title, "
                + "created_at, updated_at) VALUES (?, '2026-01-02 10:00:00', '탈퇴 대상', now(6), now(6))",
                recordId);
        Long eventId = jdbcTemplate.queryForObject(
                "SELECT timeline_event_id FROM timeline_events WHERE daily_record_id = ?", Long.class, recordId);
        jdbcTemplate.update("INSERT INTO timeline_items (item_type, raw_id, payload, created_at, updated_at) "
                + "VALUES ('LOCATION', ?, '{}', now(6), now(6))", UUID.randomUUID().toString());
        Long itemId = jdbcTemplate.queryForObject("SELECT max(timeline_item_id) FROM timeline_items", Long.class);
        jdbcTemplate.update("INSERT INTO timeline_event_items (timeline_event_id, timeline_item_id) "
                + "VALUES (?, ?)", eventId, itemId);

        UUID resolved = accountErasureService.resolveTarget(userId);

        accountErasureService.deleteContentGraph(resolved);
        accountErasureService.deleteOwnerRows(userId, resolved);
        accountErasureService.finalizeErasure(jobId, userId, resolved);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM daily_records WHERE subject_id = ?", Integer.class,
                subjectId.toString())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM timeline_items WHERE timeline_item_id = ?", Integer.class, itemId))
                .isZero();
        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(mappingCount(subjectId)).isZero();

        createdUserIds.clear();
        createdSubjectIds.clear();
    }

    /** 콘텐츠가 남아 있으면 mapping 삭제가 FK에 막혀 finalization 전체가 rollback된다(fail-closed). */
    @Test
    void 콘텐츠를_지우지_않고_finalization하면_전부_되돌아온다() {
        long userId = withdrawnUser();
        UUID subjectId = createdSubjectIds.get(createdSubjectIds.size() - 1);
        long jobId = jobIdOf(userId);
        jdbcTemplate.update("INSERT INTO daily_records (subject_id, record_date, record_at, "
                        + "record_timezone, status, created_at, updated_at) "
                        + "VALUES (?, '2026-01-03', '2026-01-03 10:00:00', 'Asia/Seoul', 'SAVED', now(6), now(6))",
                subjectId.toString());
        accountErasureService.deleteOwnerRows(userId, subjectId);

        assertThatThrownBy(() -> accountErasureService.finalizeErasure(jobId, userId, subjectId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(mappingCount(subjectId)).isOne();
        assertThat(accountErasureJobRepository.findById(jobId)).isPresent();
        assertThat(userRepository.findById(userId)).isPresent();

        jdbcTemplate.update("DELETE FROM daily_records WHERE subject_id = ?", subjectId.toString());
    }

    private long jobIdOf(long userId) {
        return accountErasureJobRepository.findAll().stream()
                .filter(job -> job.getUserId() == userId)
                .findFirst()
                .orElseThrow()
                .getAccountErasureJobId();
    }

    private int countBySubject(String table, UUID subjectId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE subject_id = ?", Integer.class, subjectId.toString());
    }

    private long insertSavedRecord(UUID subjectId, String recordDate) {
        jdbcTemplate.update("INSERT INTO daily_records (subject_id, record_date, record_at, "
                        + "record_timezone, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'Asia/Seoul', 'SAVED', now(6), now(6))",
                subjectId.toString(), recordDate, recordDate + " 10:00:00");
        return jdbcTemplate.queryForObject(
                "SELECT daily_record_id FROM daily_records WHERE subject_id = ? AND record_date = ?",
                Long.class, subjectId.toString(), recordDate);
    }

    private List<Long> pendingRecordIds(UUID subjectId) {
        return userMemoryUpdatePendingStore.findPending(Instant.now(), 10_000).scanned().stream()
                .filter(pending -> pending.subjectId().equals(subjectId))
                .map(UserMemoryUpdatePending::dailyRecordId)
                .toList();
    }

    /** worker와 같이 한 건씩 잡는다(claim 크기 1). */
    private List<AccountErasureJob> claimOne(LocalDateTime todayStart) {
        return accountErasureJobService.claimForDelete(
                todayStart.minusDays((long) GRACE_DAYS + WINDOW_DAYS),
                todayStart.minusDays(GRACE_DAYS),
                todayStart,
                todayStart.plusHours(2),
                1);
    }

    private List<List<AccountErasureJob>> claimConcurrently(LocalDateTime todayStart) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<AccountErasureJob>> first = executor.submit(() -> {
                start.await();
                return claimOne(todayStart);
            });
            Future<List<AccountErasureJob>> second = executor.submit(() -> {
                start.await();
                return claimOne(todayStart);
            });
            start.countDown();
            return List.of(first.get(), second.get());
        }
    }

    private void addDisjoint(Set<Long> claimedIds, List<AccountErasureJob> batch) {
        if (batch.isEmpty()) {
            return; // SKIP LOCKED로 경합에서 밀린 호출은 빈 결과를 받을 수 있다.
        }
        List<Long> batchIds = batch.stream().map(AccountErasureJob::getAccountErasureJobId).toList();
        assertThat(claimedIds).doesNotContainAnyElementsOf(batchIds);
        claimedIds.addAll(batchIds);
    }
}
