package com.laimory.server.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.auth.RefreshTokenStatus;
import com.laimory.server.auth.entity.RefreshToken;
import com.laimory.server.auth.repository.RefreshTokenRepository;
import com.laimory.server.auth.token.AuthTokens;
import com.laimory.server.common.error.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * RefreshTokenService ↔ 실 MySQL 회전/재사용 탐지/폐기 왕복 검증.
 * 실행: docker compose up -d 후 ./gradlew integrationTest (refresh_tokens 테이블 필요).
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class RefreshTokenRotationIntegrationTest {

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    /** 다른 테스트 데이터와 충돌하지 않도록 큰 랜덤 userId를 쓴다. */
    private long randomUserId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
    }

    private void deleteRows(long userId) {
        List<RefreshToken> rows = new ArrayList<>(refreshTokenRepository.findAll());
        rows.stream().filter(r -> r.getUserId() == userId).forEach(refreshTokenRepository::delete);
    }

    @Test
    void reusedOldToken_afterRotation_revokesAllUserRowsAndThrows2003() {
        long userId = randomUserId();
        try {
            String first = refreshTokenService.issue(userId);
            RefreshTokenService.Rotation rotated = refreshTokenService.rotate(first);
            assertThat(rotated.refreshToken()).isNotEqualTo(first);

            // 구 토큰 재제시 = 재사용 탐지 → 2003 + 해당 userId 전체 REVOKED.
            assertThatThrownBy(() -> refreshTokenService.rotate(first))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(-2003));

            List<RefreshToken> userRows = refreshTokenRepository.findAll().stream()
                    .filter(r -> r.getUserId() == userId)
                    .toList();
            assertThat(userRows).isNotEmpty();
            assertThat(userRows).allMatch(r -> r.getStatus() == RefreshTokenStatus.REVOKED);
        } finally {
            deleteRows(userId);
        }
    }

    @Test
    void revokedTokenViaLogout_cannotRotate_throws2003() {
        long userId = randomUserId();
        try {
            String raw = refreshTokenService.issue(userId);
            refreshTokenService.revoke(raw);

            assertThatThrownBy(() -> refreshTokenService.rotate(raw))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(-2003));

            Optional<RefreshToken> row = refreshTokenRepository.findByTokenHash(AuthTokens.sha256Hex(raw));
            assertThat(row).get()
                    .extracting(RefreshToken::getStatus)
                    .isEqualTo(RefreshTokenStatus.REVOKED);
        } finally {
            deleteRows(userId);
        }
    }

    /**
     * 같은 raw refresh를 두 스레드가 barrier로 동시에 회전한다. claimRotation(ACTIVE→ROTATED) 원자성으로
     * 승자는 최대 1명이며, 레이스가 감지되면(loser의 2003) 해당 userId의 모든 refresh가 REVOKED가 되고
     * 살아남는 ACTIVE가 없어야 한다(RFC 9700 rotation replay 대응). 두 스레드 모두 성공하면 버그 재발이다.
     */
    @Test
    void concurrentRotateWithSameToken_endsWithNoSurvivingActiveToken() throws Exception {
        long userId = randomUserId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            String first = refreshTokenService.issue(userId);

            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<RefreshTokenService.Rotation> task = () -> {
                barrier.await(20, TimeUnit.SECONDS); // 두 스레드 동시 시작 정렬
                return refreshTokenService.rotate(first);
            };
            Future<RefreshTokenService.Rotation> f1 = pool.submit(task);
            Future<RefreshTokenService.Rotation> f2 = pool.submit(task);

            int successes = 0;
            int reuseDetected = 0; // ERROR_2003 = 재사용/레이스 loser
            for (Future<RefreshTokenService.Rotation> f : List.of(f1, f2)) {
                try {
                    f.get(20, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException e) {
                    // 스레드에서 던져진 예외는 ExecutionException으로 감싸진다 → getCause()로 언래핑.
                    Throwable cause = e.getCause();
                    assertThat(cause).isInstanceOf(BusinessException.class);
                    assertThat(((BusinessException) cause).getErrorCode()).isEqualTo(-2003);
                    reuseDetected++;
                }
            }

            // claimRotation 원자성: 승자는 최대 1명. 둘 다 성공 = 버그 재발.
            assertThat(successes).isLessThanOrEqualTo(1);

            // 레이스가 감지되면(loser 발생) 해당 userId 전체 REVOKED, ACTIVE 0개여야 한다.
            if (reuseDetected > 0) {
                List<RefreshToken> userRows = refreshTokenRepository.findAll().stream()
                        .filter(r -> r.getUserId() == userId)
                        .toList();
                assertThat(userRows).isNotEmpty();
                assertThat(userRows).allMatch(r -> r.getStatus() == RefreshTokenStatus.REVOKED);
                assertThat(userRows).noneMatch(r -> r.getStatus() == RefreshTokenStatus.ACTIVE);
            }
        } finally {
            pool.shutdown();
            deleteRows(userId);
        }
    }
}
