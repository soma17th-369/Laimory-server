package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.auth.RefreshTokenStatus;
import com.laimory.server.auth.entity.RefreshToken;
import com.laimory.server.auth.repository.RefreshTokenRepository;
import com.laimory.server.auth.service.AppCodeService;
import com.laimory.server.auth.service.AuthTokenService;
import com.laimory.server.auth.service.RefreshTokenService;
import com.laimory.server.auth.token.AuthTokens;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.repository.PushRegistrationRepository;
import com.laimory.server.push.service.PushRegistrationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 회원 탈퇴 ↔ 실 MySQL·Redis 왕복 검증(#305).
 *
 * <p>단일 transaction commit(상태·탈퇴 시각·identity release·refresh 전량 폐기·push 삭제·PENDING job),
 * 부분 실패 전체 rollback, 멱등·동시 탈퇴의 단일 job 수렴, 즉시 재가입의 새 userId·새 subject 수렴과
 * 탈퇴→재가입→재탈퇴 generation별 nullable UNIQUE, stale Kakao nickname 갱신의 부활 차단, 탈퇴 뒤
 * app-code 교환 -2002/refresh 회전 -2003(INFO — WARN 재사용 아님) 수렴을 검증한다.
 *
 * 실행: docker compose up -d --wait 후 ./gradlew integrationTest
 * (users status/withdrawal_requested_at/provider_user_id NULL 완화와 account_erasure_jobs DDL 필요 —
 * 기존 로컬 volume엔 수동 호환 DDL 또는 fresh volume)
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class UserWithdrawalIntegrationTest {

    @Autowired
    private NewUserProvisioner newUserProvisioner;
    @Autowired
    private UserWithdrawalService userWithdrawalService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserAccountService userAccountService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SubjectMappingService subjectMappingService;
    @Autowired
    private SubjectLookupKeyDeriver subjectLookupKeyDeriver;
    @Autowired
    private UserSubjectLinkRepository userSubjectLinkRepository;
    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private PushRegistrationService pushRegistrationService;
    @Autowired
    private PushRegistrationRepository pushRegistrationRepository;
    @Autowired
    private AccountErasureJobRepository accountErasureJobRepository;
    @Autowired
    private AppCodeService appCodeService;
    @Autowired
    private AuthTokenService authTokenService;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<UUID> createdSubjectIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // FK/UNIQUE 순서: job(users FK RESTRICT) → refresh/push(soft ref) → user → mapping.
        accountErasureJobRepository.findAll().stream()
                .filter(job -> createdUserIds.contains(job.getUserId()))
                .forEach(accountErasureJobRepository::delete);
        refreshTokenRepository.findAll().stream()
                .filter(token -> createdUserIds.contains(token.getUserId()))
                .forEach(refreshTokenRepository::delete);
        createdSubjectIds.forEach(pushRegistrationRepository::deleteAllBySubjectId);
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.forEach(userId ->
                userSubjectLinkRepository.deleteById(subjectLookupKeyDeriver.deriveCurrent(userId)));
        createdUserIds.clear();
        createdSubjectIds.clear();
    }

    private String randomProviderUserId() {
        return "withdrawal-it-" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
    }

    private User provision(String providerUserId, String nickname) {
        User user = newUserProvisioner.provision(Provider.KAKAO, providerUserId, null, nickname);
        createdUserIds.add(user.getUserId());
        createdSubjectIds.add(subjectMappingService.getRequired(user.getUserId()));
        return user;
    }

    private List<AccountErasureJob> jobsOf(long userId) {
        return accountErasureJobRepository.findAll().stream()
                .filter(job -> job.getUserId().equals(userId))
                .toList();
    }

    @Test
    void withdraw_commitsStatusIdentityRefreshPushAndSingleJobInOneTransaction() {
        User user = provision(randomProviderUserId(), "탈퇴전닉");
        long userId = user.getUserId();
        UUID subjectId = createdSubjectIds.get(createdSubjectIds.size() - 1);
        refreshTokenService.issue(userId);
        refreshTokenService.issue(userId);
        pushRegistrationService.register("v1", subjectId, "fid-" + userId);

        userWithdrawalService.withdraw("v1", userId);

        User withdrawn = userRepository.findById(userId).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(UserStatus.WITHDRAWAL_PENDING);
        assertThat(withdrawn.getWithdrawalRequestedAt()).isNotNull(); // 서버 시각 기록
        assertThat(withdrawn.getProviderUserId()).isNull();           // identity release — 같은 UPDATE
        assertThat(userAccountService.isActive(userId)).isFalse();    // 이후 /a/api ACTIVE 검사는 전부 401

        List<RefreshToken> refreshRows = refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUserId() == userId)
                .toList();
        assertThat(refreshRows).hasSize(2)
                .allMatch(token -> token.getStatus() == RefreshTokenStatus.REVOKED);
        assertThat(pushRegistrationRepository.findAllFirebaseInstallationIdsBySubjectId(subjectId)).isEmpty();

        List<AccountErasureJob> jobs = jobsOf(userId);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getStatus()).isEqualTo(AccountErasureJobStatus.PENDING);
        assertThat(jobs.get(0).getCreatedAt()).isNotNull(); // 접수 감사 시각(runbook 수동 backlog 확인 기준)
    }

    @Test
    void withdraw_repeatedCall_convergesIdempotentlyToSingleJob() {
        User user = provision(randomProviderUserId(), null);
        long userId = user.getUserId();

        userWithdrawalService.withdraw("v1", userId);
        // 이미 인증을 통과한 요청의 재도착(응답 유실 재시도와 같은 경로) — 예외 없이 202 수렴, 부수효과 없음.
        assertThatCode(() -> userWithdrawalService.withdraw("v1", userId)).doesNotThrowAnyException();

        assertThat(jobsOf(userId)).hasSize(1);
        assertThat(userRepository.findById(userId).orElseThrow().getStatus())
                .isEqualTo(UserStatus.WITHDRAWAL_PENDING);
    }

    /** 같은 회원의 동시 탈퇴 — CAS 승자 1명만 정리를 수행하고 둘 다 성공하며 job은 정확히 하나다. */
    @Test
    void concurrentWithdrawals_bothSucceed_withSingleJobAndNoPartialState() throws Exception {
        User user = provision(randomProviderUserId(), null);
        long userId = user.getUserId();
        refreshTokenService.issue(userId);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Void> task = () -> {
                barrier.await(20, TimeUnit.SECONDS);
                userWithdrawalService.withdraw("v1", userId);
                return null;
            };
            Future<Void> f1 = pool.submit(task);
            Future<Void> f2 = pool.submit(task);
            f1.get(20, TimeUnit.SECONDS); // 예외 없이 완료 = 멱등 202 수렴
            f2.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        User withdrawn = userRepository.findById(userId).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(UserStatus.WITHDRAWAL_PENDING);
        assertThat(withdrawn.getProviderUserId()).isNull();
        assertThat(jobsOf(userId)).hasSize(1);
        assertThat(refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUserId() == userId)
                .toList()).isNotEmpty()
                .allMatch(token -> token.getStatus() == RefreshTokenStatus.REVOKED);
    }

    /** 탈퇴 commit 직후 같은 provider 로그인은 old row 재활성화가 아니라 새 userId·새 subject 신규 가입이다. */
    @Test
    void rejoinAfterWithdrawal_createsNewGeneration_andRepeatedCycleKeepsUniqueReleased() {
        String providerUserId = randomProviderUserId();
        User gen1 = provision(providerUserId, "1세대닉");
        UUID gen1Subject = createdSubjectIds.get(createdSubjectIds.size() - 1);
        userWithdrawalService.withdraw("v1", gen1.getUserId());

        // 같은 소셜 계정 재로그인 — findOrCreate가 released identity로 완전히 새 회원을 만든다.
        User gen2 = userService.findOrCreate(Provider.KAKAO, providerUserId, null, "2세대닉");
        createdUserIds.add(gen2.getUserId());
        UUID gen2Subject = subjectMappingService.getRequired(gen2.getUserId());
        createdSubjectIds.add(gen2Subject);

        assertThat(gen2.getUserId()).isNotEqualTo(gen1.getUserId());
        assertThat(gen2.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(gen2.getProviderUserId()).isEqualTo(providerUserId);
        assertThat(gen2.getNickname()).isEqualTo("2세대닉"); // old 닉네임·데이터와 비연결
        assertThat(gen2Subject).isNotEqualTo(gen1Subject);   // 콘텐츠 subject도 새로 발급 — 과거 콘텐츠 비연결
        assertThat(userAccountService.isActive(gen1.getUserId())).isFalse(); // old row는 그대로 탈퇴 상태

        // 재탈퇴 — nullable UNIQUE가 NULL identity를 여럿 허용해 generation별 old row·job이 남는다.
        userWithdrawalService.withdraw("v1", gen2.getUserId());
        assertThat(jobsOf(gen1.getUserId())).hasSize(1);
        assertThat(jobsOf(gen2.getUserId())).hasSize(1);

        // 3세대 재가입까지 — 탈퇴 NULL 행 2개와 UNIQUE 충돌 없이 다시 신규 가입된다.
        User gen3 = userService.findOrCreate(Provider.KAKAO, providerUserId, null, "3세대닉");
        createdUserIds.add(gen3.getUserId());
        createdSubjectIds.add(subjectMappingService.getRequired(gen3.getUserId()));
        assertThat(gen3.getUserId()).isNotIn(gen1.getUserId(), gen2.getUserId());
        assertThat(gen3.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    /** 탈퇴와 겹친 stale Kakao nickname 갱신이 old row의 status·released identity를 되살리지 못한다. */
    @Test
    void staleKakaoNicknameUpdate_afterWithdrawal_doesNotTouchWithdrawnRow() {
        String providerUserId = randomProviderUserId();
        User user = provision(providerUserId, "옛닉");
        long userId = user.getUserId();
        userWithdrawalService.withdraw("v1", userId);

        // 탈퇴 전에 old row를 읽은 로그인 흐름의 갱신 시도 — ACTIVE 조건이라 0행이다(부활 없음).
        int updated = userRepository.updateNicknameIfActive(Provider.KAKAO, providerUserId, "새닉");

        assertThat(updated).isZero();
        User withdrawn = userRepository.findById(userId).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(UserStatus.WITHDRAWAL_PENDING);
        assertThat(withdrawn.getProviderUserId()).isNull();
        assertThat(withdrawn.getNickname()).isEqualTo("옛닉");
    }

    /** 어느 단계 실패든 전부 rollback — 회원은 ACTIVE로 남고 refresh·job 부수효과가 없다. */
    @Test
    void withdrawalStepFailure_rollsBackWholeTransaction_leavingUserActive() {
        // subject mapping 없이 만든 회원 — 탈퇴 transaction의 getRequired가 fail-closed로 던진다(실패 주입).
        String providerUserId = randomProviderUserId();
        User user = userRepository.save(User.of(Provider.KAKAO, providerUserId, null, "닉"));
        createdUserIds.add(user.getUserId());
        long userId = user.getUserId();
        String rawRefresh = refreshTokenService.issue(userId);

        assertThatThrownBy(() -> userWithdrawalService.withdraw("v1", userId))
                .isInstanceOf(IllegalStateException.class);

        User stillActive = userRepository.findById(userId).orElseThrow();
        assertThat(stillActive.getStatus()).isEqualTo(UserStatus.ACTIVE);       // ① 전이 rollback
        assertThat(stillActive.getProviderUserId()).isEqualTo(providerUserId); // identity 보존
        assertThat(stillActive.getWithdrawalRequestedAt()).isNull();
        assertThat(refreshTokenRepository.findByTokenHash(AuthTokens.sha256Hex(rawRefresh)))
                .get().extracting(RefreshToken::getStatus)
                .isEqualTo(RefreshTokenStatus.ACTIVE);                          // ③ revoke rollback
        assertThat(jobsOf(userId)).isEmpty();                                   // ⑤ job rollback
    }

    /** 탈퇴 뒤 시작된 app-code 교환은 신규 코드 없이 기존 401 -2002로 수렴한다(#305 §5.4). */
    @Test
    void appCodeExchangeAfterWithdrawal_convergesToAppCodeInvalid2002() {
        User user = provision(randomProviderUserId(), null);
        long userId = user.getUserId();
        String verifier = "withdrawal-it-verifier";
        String appCode = appCodeService.issue(userId, AuthTokens.challenge(verifier));

        userWithdrawalService.withdraw("v1", userId);

        assertThatThrownBy(() -> authTokenService.issueTokens("v1", appCode, verifier))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getExceptionType()).isEqualTo(ExceptionType.APP_CODE_INVALID);
                    assertThat(e.getErrorCode()).isEqualTo(-2002);
                });
        // 발급이 하나도 남지 않는다.
        assertThat(refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUserId() == userId)
                .filter(token -> token.getStatus() == RefreshTokenStatus.ACTIVE)
                .toList()).isEmpty();
    }

    /**
     * 탈퇴 뒤 시작된 refresh 회전은 — 탈퇴가 폐기한 행이든 race로 늦게 저장된 ACTIVE 행이든 —
     * WARN 재사용 경로가 아니라 기존 401 -2003 {@code REFRESH_TOKEN_INVALID}(INFO)로 수렴한다.
     */
    @Test
    void refreshRotationAfterWithdrawal_convergesToInvalid2003_notWarnReuse() {
        User user = provision(randomProviderUserId(), null);
        long userId = user.getUserId();
        String revokedByWithdrawal = refreshTokenService.issue(userId);

        userWithdrawalService.withdraw("v1", userId);

        // ① 탈퇴가 REVOKED로 만든 행 — 재사용 탐지(REUSED·WARN·전체 폐기)가 아니라 INVALID(INFO)다.
        assertThatThrownBy(() -> authTokenService.refresh("v1", revokedByWithdrawal))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getExceptionType()).isEqualTo(ExceptionType.REFRESH_TOKEN_INVALID);
                    assertThat(e.getErrorCode()).isEqualTo(-2003);
                });

        // ② in-flight 교환이 race로 늦게 저장한 ACTIVE 행 시뮬레이션 — 발급 전 ACTIVE 검사가 거절하고
        //    행은 물리적으로 남는다(#302 정리 대상 — 202는 사용·연장 불가를 뜻하지 zero가 아님).
        String lateSavedActive = refreshTokenService.issue(userId);
        assertThatThrownBy(() -> authTokenService.refresh("v1", lateSavedActive))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getExceptionType()).isEqualTo(ExceptionType.REFRESH_TOKEN_INVALID));
        assertThat(refreshTokenRepository.findByTokenHash(AuthTokens.sha256Hex(lateSavedActive)))
                .get().extracting(RefreshToken::getStatus)
                .isEqualTo(RefreshTokenStatus.ACTIVE);
    }
}
