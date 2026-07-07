package com.laimory.server.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.auth.RefreshTokenStatus;
import com.laimory.server.auth.entity.RefreshToken;
import com.laimory.server.auth.repository.RefreshTokenRepository;
import com.laimory.server.auth.token.AuthTokens;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
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
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_2003));

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
                            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ERROR_2003));

            Optional<RefreshToken> row = refreshTokenRepository.findByTokenHash(AuthTokens.sha256Hex(raw));
            assertThat(row).get()
                    .extracting(RefreshToken::getStatus)
                    .isEqualTo(RefreshTokenStatus.REVOKED);
        } finally {
            deleteRows(userId);
        }
    }
}
