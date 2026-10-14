package com.laimory.server.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.auth.entity.RefreshToken;
import com.laimory.server.auth.repository.RefreshTokenRepository;
import com.laimory.server.auth.token.AuthTokens;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 만료 refresh token 정리(#514)를 실 MySQL에서 검증한다. 고정 Clock을 2020년에 두어 cutoff 이전 행이 이 테스트가 만든
 * 행뿐이게 한다 — 공유 로컬 DB의 다른 테스트 행(만료가 실제 현재 이후)은 정리 대상이 되지 않는다.
 * 실행: docker compose up -d 후 ./gradlew integrationTest.
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class RefreshTokenSweeperIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2020, 1, 10, 0, 0);
    private static final LocalDateTime CUTOFF = NOW.minusDays(1);

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private final long userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);

    @AfterEach
    void deleteRows() {
        refreshTokenRepository.deleteAllByUserId(userId);
    }

    @Test
    void deletesRowsExpiredMoreThanOneDayAgoRegardlessOfStatus() {
        String active = saveToken(CUTOFF.minusHours(1));
        String rotated = saveToken(CUTOFF.minusHours(1));
        refreshTokenRepository.claimRotation(idOf(rotated));
        String revoked = saveToken(CUTOFF.minusHours(1));
        refreshTokenRepository.revokeByTokenHash(revoked);

        sweeper().sweepExpiredRefreshTokens();

        assertThat(refreshTokenRepository.findByTokenHash(active)).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(rotated)).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(revoked)).isEmpty();
    }

    @Test
    void keepsRowsExpiredWithinGraceAndRowsNotYetExpired() {
        String expiredWithinGrace = saveToken(NOW.minusHours(1));
        String rotatedNotYetExpired = saveToken(NOW.plusDays(20));
        refreshTokenRepository.claimRotation(idOf(rotatedNotYetExpired));

        sweeper().sweepExpiredRefreshTokens();

        assertThat(refreshTokenRepository.findByTokenHash(expiredWithinGrace)).isPresent();
        assertThat(refreshTokenRepository.findByTokenHash(rotatedNotYetExpired)).isPresent();
    }

    @Test
    void keepsRowExpiringExactlyAtCutoff() {
        String atCutoff = saveToken(CUTOFF);

        sweeper().sweepExpiredRefreshTokens();

        assertThat(refreshTokenRepository.findByTokenHash(atCutoff)).isPresent();
    }

    private RefreshTokenSweeper sweeper() {
        return new RefreshTokenSweeper(refreshTokenService, Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL));
    }

    /** 행을 저장하고 조회 키인 token hash를 돌려준다. */
    private String saveToken(LocalDateTime expiresAt) {
        String tokenHash = AuthTokens.sha256Hex(AuthTokens.generate());
        refreshTokenRepository.save(RefreshToken.issue(userId, tokenHash, expiresAt, null));
        return tokenHash;
    }

    private long idOf(String tokenHash) {
        return refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow().getRefreshTokenId();
    }
}
