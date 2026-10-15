package com.laimory.server.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료 후 {@link #EXPIRY_GRACE}가 지난 refresh token 행을 하루 1회 지운다(#514). 만료된 토큰은 rotate가 isExpired에서
 * 끊어 행 존재가 동작에 영향이 없으므로 상태와 무관하게 지운다. 만료 전 ROTATED/REVOKED 행은 재사용 탐지 증거라 대상이
 * 아니다.
 *
 * <p>모든 인스턴스가 같은 시각에 같은 조건 DELETE 한 문장을 실행한다. 멱등이라 먼저 잡은 쪽이 지우고 나머지는 잠깐
 * 기다린 뒤 0행이며, 한 대가 멈춰도 다른 인스턴스가 전부 지운다 — 행마다 DB 밖 작업이 없어 orphan·draft 정리 같은
 * 서버별 분배를 두지 않는다. 만료 비율이 높아 전체 스캔이 되어도 READ COMMITTED(#576)에서는 만료 행만 잠긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenSweeper {

    /**
     * 만료부터 삭제까지의 유예. rotate가 만료 검사를 통과한 직후 그 행이 지워지면 claimRotation이 0행이 되어 재사용으로
     * 오판(사용자 refresh 전체 폐기)한다 — 요청 처리 시간보다 충분히 길게 둬 그 경로를 없앤다.
     */
    static final Duration EXPIRY_GRACE = Duration.ofDays(1);

    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    @Scheduled(
            cron = "${app.auth.refresh-token-sweep.cron:0 0 3 * * *}",
            zone = "${app.auth.refresh-token-sweep.zone:Asia/Seoul}")
    public void sweepExpiredRefreshTokens() {
        long startedAtNanos = System.nanoTime();
        // expires_at을 발급할 때와 같은 application Clock(Asia/Seoul 벽시계)으로 cutoff를 계산한다.
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(EXPIRY_GRACE);
        try {
            int deleted = refreshTokenService.deleteAllExpiredBefore(cutoff);
            log.info("만료 refresh token 정리 완료: deleted={} durationMs={}", deleted, elapsedMillis(startedAtNanos));
        } catch (RuntimeException exception) {
            log.warn("만료 refresh token 정리 실패(다음 실행에서 재시도): exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }
}
