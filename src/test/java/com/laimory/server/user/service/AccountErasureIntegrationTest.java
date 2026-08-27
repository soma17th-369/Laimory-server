package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.user.AccountErasureJobStatus;
import com.laimory.server.user.Provider;
import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.entity.AccountErasureJob;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.repository.AccountErasureJobRepository;
import com.laimory.server.user.repository.UserRepository;
import com.laimory.server.user.repository.UserSubjectLinkRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * 계정 삭제 worker ↔ 실 MySQL 왕복 검증(#302 PR1).
 *
 * <p>검증하는 것은 claim 경계와 삭제 순서다.
 * <ul>
 *   <li>처리 창 — 접수일 D 기준 D+8~D+10만 claim되고, D+7은 이르고 D+11은 만료다(PHOTO 삭제 #365와 동일).</li>
 *   <li>같은 날 재선택 방지와 다음 날 재claim.</li>
 *   <li>정지 자격이 {@code quiesce-delay}로만 결정된다 — 접수 insert가 두 감사 컬럼에 같은 값을 넣으므로
 *       {@code stale-after}를 크게 잡으면 실효 gate가 밀린다(그래서 properties가 부등식을 강제한다).</li>
 *   <li>{@code account_erasure_jobs}가 남은 회원 행은 지울 수 없다(FK RESTRICT).</li>
 *   <li>콘텐츠가 없는 회원의 접수 → 정지 → 삭제 E2E.</li>
 * </ul>
 *
 * 실행: docker compose up -d --wait 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class AccountErasureIntegrationTest {

    private static final int GRACE_DAYS = 7;
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

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<UUID> createdSubjectIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(userId -> jdbcTemplate.update(
                "DELETE FROM account_erasure_jobs WHERE user_id = ?", userId));
        createdSubjectIds.forEach(subjectId -> {
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

    private long withdrawnUser() {
        User user = newUserProvisioner.provision(Provider.KAKAO,
                "erasure-it-" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L),
                null, "탈퇴예정");
        createdUserIds.add(user.getUserId());
        createdSubjectIds.add(subjectOf(user.getUserId()));
        userWithdrawalService.withdraw("1", user.getUserId());
        return user.getUserId();
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
        backdate(userId, todayStart.minusDays(GRACE_DAYS).plusHours(1)); // D+7 — 아직 이르다
        accountErasureJobService.transition(jobIdOf(userId),
                AccountErasureJobStatus.PENDING, AccountErasureJobStatus.QUIESCED);

        assertThat(claimed(claimForDelete(todayStart), userId)).isFalse();
    }

    @Test
    void 처리_창_안의_접수만_claim된다() {
        long userId = withdrawnUser();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        accountErasureJobService.transition(jobIdOf(userId),
                AccountErasureJobStatus.PENDING, AccountErasureJobStatus.QUIESCED);

        // D+8(창의 첫날)
        backdate(userId, todayStart.minusDays(GRACE_DAYS + 1L).plusHours(3));
        assertThat(claimed(claimForDelete(todayStart), userId)).isTrue();

        // D+10(창의 마지막 날)
        backdate(userId, todayStart.minusDays((long) GRACE_DAYS + WINDOW_DAYS).plusHours(3));
        assertThat(claimed(claimForDelete(todayStart), userId)).isTrue();
    }

    @Test
    void 창을_벗어난_접수는_재시도하지_않고_만료로_집계된다() {
        long userId = withdrawnUser();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        accountErasureJobService.transition(jobIdOf(userId),
                AccountErasureJobStatus.PENDING, AccountErasureJobStatus.QUIESCED);
        backdate(userId, todayStart.minusDays((long) GRACE_DAYS + WINDOW_DAYS + 1).minusHours(1));

        assertThat(claimed(claimForDelete(todayStart), userId)).isFalse();
        assertThat(accountErasureJobService.countExpired(
                todayStart.minusDays((long) GRACE_DAYS + WINDOW_DAYS))).isPositive();
    }

    @Test
    void 같은_날_두_번_claim되지_않고_다음_날_다시_잡힌다() {
        long userId = withdrawnUser();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        accountErasureJobService.transition(jobIdOf(userId),
                AccountErasureJobStatus.PENDING, AccountErasureJobStatus.QUIESCED);
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
        accountErasureJobService.markManualReview(jobIdOf(userId), AccountErasureJobStatus.PENDING);

        assertThat(claimed(claimForDelete(todayStart), userId)).isFalse();
        assertThat(accountErasureJobService.countManualReview()).isPositive();
    }

    @Test
    void 정지_자격은_접수_시각과_quiesce_delay로만_결정된다() {
        long userId = withdrawnUser();
        LocalDateTime now = LocalDateTime.now();
        backdate(userId, now.minusMinutes(30));

        // quiesce-delay 20m 경과 · stale-after 15m 경과 → 대상
        List<AccountErasureJob> claimed = accountErasureJobService.claimForQuiesce(
                now.minusMinutes(20), now.minusMinutes(15), now, LIMIT);
        assertThat(claimed(claimed, userId)).isTrue();

        // 방금 claim했으므로 stale 창 안에서는 다시 잡히지 않는다
        assertThat(claimed(accountErasureJobService.claimForQuiesce(
                now.minusMinutes(20), now.minusMinutes(15), now, LIMIT), userId)).isFalse();
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

        accountErasureService.quiesce(resolved);
        assertThat(accountErasureJobService.transition(
                jobId, AccountErasureJobStatus.PENDING, AccountErasureJobStatus.QUIESCED)).isTrue();

        accountErasureService.deleteOwnerRows(userId, resolved);
        assertThat(accountErasureService.finalizeErasure(jobId, userId, resolved)).isTrue();

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
    void 경쟁에서_진_worker의_finalization은_회원_행을_지우지_않는다() {
        long userId = withdrawnUser();
        UUID subjectId = createdSubjectIds.get(createdSubjectIds.size() - 1);
        long jobId = jobIdOf(userId);
        accountErasureJobService.transition(jobId,
                AccountErasureJobStatus.PENDING, AccountErasureJobStatus.QUIESCED);
        accountErasureService.deleteOwnerRows(userId, subjectId);

        assertThat(accountErasureService.finalizeErasure(jobId, userId, subjectId)).isTrue();
        // 두 번째 호출은 mapping이 이미 없어 0행 — 회원 행을 건드리지 않고 false로 수렴한다.
        assertThat(accountErasureService.finalizeErasure(jobId, userId, subjectId)).isFalse();

        createdUserIds.clear();
        createdSubjectIds.clear();
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
}
