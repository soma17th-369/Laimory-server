package com.laimory.server.geo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * geo enrich·Kakao HTTP·retry·circuit의 custom meter 발행 지점(단일 소유). 이름·증가 시점·허용 tag는
 * 계약이다 — 좌표·주소·query·URL·API key·response body·예외 자유 문자열을 <b>절대</b> tag/log에 넣지 않는다.
 *
 * <p>허용 tag 값(저카디널리티 고정 집합):
 * <ul>
 *   <li>{@code outcome}: batch는 {@code success|partial|rejected|bug}, logical call은
 *       {@code success|exhausted|local_rejected|not_permitted}</li>
 *   <li>{@code failure_kind}: {@code none|transient|permanent|mixed}</li>
 *   <li>{@code endpoint}: {@code coord2address|keyword}</li>
 *   <li>{@code attempt}: {@code first|retry}</li>
 * </ul>
 *
 * <p>Reactor Netty native pool meter({@code reactor.netty.connection.provider.*})와 Resilience4j binder,
 * 기존 {@code http.client.requests}는 별도 유지되며 여기서 중복 계수하지 않는다.
 */
@Component
public class GeoMetrics {

    static final String BATCH_TIMER = "laimory.geo.batch";
    static final String LOGICAL_TIMER = "laimory.geo.http.logical";
    static final String ATTEMPTS_COUNTER = "laimory.geo.http.attempts";
    static final String RETRIES_COUNTER = "laimory.geo.http.retries";
    static final String CIRCUIT_TRANSITIONS_COUNTER = "laimory.geo.circuit.transitions";

    private final MeterRegistry meterRegistry;

    public GeoMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** unique lookup batch terminal마다 정확히 1회 — enrich 정책 판정까지 끝난 시점에 기록한다. */
    public void recordBatch(String outcome, String failureKind, Duration duration) {
        Timer.builder(BATCH_TIMER)
                .tag("outcome", outcome)
                .tag("failure_kind", failureKind)
                .register(meterRegistry)
                .record(duration);
    }

    /** coord2address/keyword logical call(retry·deadline 포함) terminal마다 1회. */
    public void recordLogicalCall(String endpoint, String outcome, String failureKind, Duration duration) {
        Timer.builder(LOGICAL_TIMER)
                .tag("endpoint", endpoint)
                .tag("outcome", outcome)
                .tag("failure_kind", failureKind)
                .register(meterRegistry)
                .record(duration);
    }

    /** 실제 wire attempt가 구독될 때 1회({@code attempt=first|retry}) — circuit open 거절은 세지 않는다. */
    public void countAttempt(String endpoint, boolean firstAttempt) {
        Counter.builder(ATTEMPTS_COUNTER)
                .tag("endpoint", endpoint)
                .tag("attempt", firstAttempt ? "first" : "retry")
                .register(meterRegistry)
                .increment();
    }

    /** retry가 schedule될 때 1회 — {@code failure_kind}는 직전 실패의 분류다. */
    public void countRetry(String endpoint, String failureKind) {
        Counter.builder(RETRIES_COUNTER)
                .tag("endpoint", endpoint)
                .tag("failure_kind", failureKind)
                .register(meterRegistry)
                .increment();
    }

    /** circuit state transition event마다 1회. */
    public void countCircuitTransition(String from, String to) {
        Counter.builder(CIRCUIT_TRANSITIONS_COUNTER)
                .tag("from", from)
                .tag("to", to)
                .register(meterRegistry)
                .increment();
    }

    /** {@link MapPlaceLookupException} → {@code failure_kind} tag 값. */
    public static String failureKind(MapPlaceLookupException failure) {
        return failure.clientMayRetryLater() ? "transient" : "permanent";
    }

    /** {@link MapPlaceLookupException} → logical call {@code outcome} tag 값. */
    public static String logicalOutcome(MapPlaceLookupException failure) {
        return switch (failure.category()) {
            case REMOTE -> "exhausted";
            case LOCAL_REJECTED, LOGICAL_DEADLINE -> "local_rejected";
            case NOT_PERMITTED -> "not_permitted";
        };
    }
}
