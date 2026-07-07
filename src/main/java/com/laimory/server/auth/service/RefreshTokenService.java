package com.laimory.server.auth.service;

import com.laimory.server.auth.entity.RefreshToken;
import com.laimory.server.auth.repository.RefreshTokenRepository;
import com.laimory.server.auth.token.AuthTokens;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * refresh token leaf 서비스(발급·회전·폐기·재사용 탐지). 자신과 1:1인 {@link RefreshTokenRepository}에만 접근한다.
 *
 * <p>refresh는 일회용이다 — 사용(회전)할 때마다 새 토큰으로 교체되고 이전 것은 ROTATED로 남는다.
 * ROTATED/REVOKED 토큰이 재제시되면(원본·복제본이 같이 돌아다닌다는 확실한 신호) 그 사용자의
 * refresh <b>전체</b>를 폐기해 도둑과 정상 사용자 모두 재로그인시킨다. 정상 앱의 동시 refresh도
 * 같은 경로로 빠질 수 있으므로 <b>클라이언트는 refresh를 single-flight로 직렬화해야 한다</b>(앱 계약).
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final TransactionTemplate transactionTemplate;
    private final Duration refreshTtl;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               PlatformTransactionManager transactionManager,
                               @Value("${app.auth.refresh-ttl}") Duration refreshTtl,
                               Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.refreshTtl = refreshTtl;
        this.clock = clock;
    }

    /** 회전 결과 — 토큰 소유자와 새 refresh 원문. */
    public record Rotation(long userId, String refreshToken) {
    }

    /** 새 refresh 토큰을 발급한다. DB에는 SHA-256 hex 해시만 저장하고 원문을 반환한다. */
    public String issue(long userId) {
        return issueChained(userId, null);
    }

    /**
     * 제시된 refresh를 원자적으로 회전한다. 무효/만료는 {@code ERROR_2003}, 재사용(이미 ROTATED/REVOKED)은
     * 사용자 전체 폐기 후 {@code ERROR_2003}.
     *
     * <p><b>회전 승자의 claim + 새 refresh 저장은 한 트랜잭션</b>으로 묶는다(아래 {@code transactionTemplate}).
     * 그 트랜잭션은 old row 락을 커밋까지 쥐므로, 같은 토큰으로 동시에 들어온 loser는 <b>승자 커밋 이후에야</b>
     * {@code claim=0}을 관측한다 — 그 시점엔 승자의 새 ACTIVE refresh까지 커밋돼 있어, loser의
     * {@code revokeAllByUserId}가 그 새 토큰까지 폐기한다(재사용 탐지 = 사용자 전체 폐기 계약 보장, RFC 9700).
     *
     * <p>반면 loser의 전체 폐기와 뒤따르는 throw는 트랜잭션 밖이다 — 폐기가 throw와 함께 롤백되면 안 되므로
     * 승자 트랜잭션이 커밋된 뒤 별도 커밋된다.
     */
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("refreshToken must not be blank");
        }
        RefreshToken current = refreshTokenRepository.findByTokenHash(AuthTokens.sha256Hex(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.ERROR_2003));
        if (current.isExpired(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.ERROR_2003);
        }

        // 승자만 새 refresh를 받는다(claim + 저장이 한 트랜잭션). loser는 null을 받는다.
        String newRefresh = transactionTemplate.execute(status -> {
            if (refreshTokenRepository.claimRotation(current.getRefreshTokenId()) != 1) {
                return null; // 이미 ROTATED/REVOKED = 재사용/레이스 loser
            }
            return issueChained(current.getUserId(), current.getRefreshTokenId());
        });

        if (newRefresh == null) {
            // 재사용 탐지: 사용자 refresh 전체 폐기(승자 커밋 후 관측·별도 커밋). 그 뒤 throw.
            int revoked = refreshTokenRepository.revokeAllByUserId(current.getUserId());
            log.warn("refresh token reuse detected: userId={} revokedCount={}", current.getUserId(), revoked);
            throw new BusinessException(ErrorCode.ERROR_2003);
        }
        return new Rotation(current.getUserId(), newRefresh);
    }

    /** 로그아웃: 해당 refresh만 폐기한다. 멱등 — 미존재/이미 폐기여도 조용히 성공(유효성 오라클 차단). */
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("refreshToken must not be blank");
        }
        refreshTokenRepository.revokeByTokenHash(AuthTokens.sha256Hex(rawToken));
    }

    private String issueChained(long userId, Long parentId) {
        String raw = AuthTokens.generate();
        refreshTokenRepository.save(RefreshToken.issue(
                userId, AuthTokens.sha256Hex(raw), LocalDateTime.now(clock).plus(refreshTtl), parentId));
        return raw;
    }
}
