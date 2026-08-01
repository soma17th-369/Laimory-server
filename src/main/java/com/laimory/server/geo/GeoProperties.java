package com.laimory.server.geo;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kakao 전용 HTTP 자원 경계(pool·timeout·retry·circuit)의 typed 설정. {@code app.geo.mode=kakao}에서만
 * 바인딩·검증된다({@link KakaoGeoHttpConfiguration}이 {@code @EnableConfigurationProperties}로 등록).
 * property 이름은 {@code application.properties}가 권위이고 같은 이름의 upper-snake env가 override한다.
 *
 * <p>값은 안전 상한을 위한 checked-in 초기 기본값이다 — 최적 처리량·p95를 보장하지 않으며 dev metric으로
 * 조정한다. 위반 시 기동 실패(fail-fast)하도록 바인딩 시점에 교차 검증한다. 메시지에 property 이름을
 * 명시해 운영 override 오류를 바로 찾게 한다.
 */
@ConfigurationProperties(prefix = "app.geo")
public record GeoProperties(int lookupConcurrency, Http http, Retry retry, Circuit circuit) {

    public GeoProperties {
        require(lookupConcurrency >= 1, "app.geo.lookup-concurrency must be >= 1");
        require(http != null, "app.geo.http.* is required");
        require(retry != null, "app.geo.retry.* is required");
        require(circuit != null, "app.geo.circuit.* is required");
        require(lookupConcurrency <= http.pool().maxConnections(),
                "app.geo.lookup-concurrency must be <= app.geo.http.pool.max-connections");
        // logical deadline이 개별 attempt+backoff budget보다 짧으면 cancellation 의미가 모호해진다(교차 검증).
        // pending queue를 0으로 override해도 timeout 값은 formula·typed 설정 일관성을 위해 유지한다.
        Duration attemptBudget = http.pool().pendingAcquireTimeout()
                .plus(http.connectTimeout())
                .plus(http.responseTimeout());
        Duration minimumDeadline = attemptBudget.multipliedBy(retry.maxAttempts())
                .plus(retry.maxBackoff().multipliedBy(retry.maxAttempts() - 1L));
        require(http.logicalCallTimeout().compareTo(minimumDeadline) >= 0,
                "app.geo.http.logical-call-timeout must be >= attempts x (pending-acquire-timeout + "
                        + "connect-timeout + response-timeout) + (attempts - 1) x app.geo.retry.max-backoff");
    }

    public record Http(Duration connectTimeout, Duration responseTimeout, Duration logicalCallTimeout, Pool pool) {

        public Http {
            requirePositive(connectTimeout, "app.geo.http.connect-timeout");
            requirePositive(responseTimeout, "app.geo.http.response-timeout");
            requirePositive(logicalCallTimeout, "app.geo.http.logical-call-timeout");
            require(pool != null, "app.geo.http.pool.* is required");
        }

        public record Pool(int maxConnections, int pendingAcquireMaxCount, Duration pendingAcquireTimeout,
                Duration maxIdleTime, Duration maxLifeTime, Duration evictionInterval) {

            public Pool {
                require(maxConnections >= 1, "app.geo.http.pool.max-connections must be >= 1");
                require(pendingAcquireMaxCount >= 0,
                        "app.geo.http.pool.pending-acquire-max-count must be >= 0");
                requirePositive(pendingAcquireTimeout, "app.geo.http.pool.pending-acquire-timeout");
                requirePositive(maxIdleTime, "app.geo.http.pool.max-idle-time");
                requirePositive(maxLifeTime, "app.geo.http.pool.max-life-time");
                requirePositive(evictionInterval, "app.geo.http.pool.eviction-interval");
                require(maxIdleTime.compareTo(maxLifeTime) < 0,
                        "app.geo.http.pool.max-idle-time must be < app.geo.http.pool.max-life-time");
                require(evictionInterval.compareTo(maxIdleTime) <= 0,
                        "app.geo.http.pool.eviction-interval must be <= app.geo.http.pool.max-idle-time");
            }
        }
    }

    public record Retry(int maxAttempts, Duration firstBackoff, Duration maxBackoff, double jitter) {

        public Retry {
            // 멱등 Kakao GET 한정 제한적 retry — 상한 2를 계약으로 고정한다(retry storm 유계화, D13).
            require(maxAttempts >= 1 && maxAttempts <= 2, "app.geo.retry.max-attempts must be 1 or 2");
            requirePositive(firstBackoff, "app.geo.retry.first-backoff");
            requirePositive(maxBackoff, "app.geo.retry.max-backoff");
            require(firstBackoff.compareTo(maxBackoff) <= 0,
                    "app.geo.retry.first-backoff must be <= app.geo.retry.max-backoff");
            require(jitter >= 0.0d && jitter < 1.0d, "app.geo.retry.jitter must be in [0, 1)");
        }
    }

    public record Circuit(int slidingWindowSize, int minimumNumberOfCalls, int failureRateThreshold,
            Duration waitDurationInOpenState, int permittedCallsInHalfOpen) {

        public Circuit {
            require(slidingWindowSize >= 1, "app.geo.circuit.sliding-window-size must be >= 1");
            require(minimumNumberOfCalls >= 1 && minimumNumberOfCalls <= slidingWindowSize,
                    "app.geo.circuit.minimum-number-of-calls must be in [1, sliding-window-size]");
            require(failureRateThreshold > 0 && failureRateThreshold <= 100,
                    "app.geo.circuit.failure-rate-threshold must be in (0, 100]");
            requirePositive(waitDurationInOpenState, "app.geo.circuit.wait-duration-in-open-state");
            require(permittedCallsInHalfOpen >= 1,
                    "app.geo.circuit.permitted-calls-in-half-open must be >= 1");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void requirePositive(Duration duration, String property) {
        require(duration != null && !duration.isZero() && !duration.isNegative(),
                property + " must be a positive duration");
    }
}
