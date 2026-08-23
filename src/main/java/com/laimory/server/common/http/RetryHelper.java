package com.laimory.server.common.http;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * 재구독 안전한 공통 reactive retry 헬퍼(opt-in). caller가 넘긴 supplier를 {@link Mono#defer}로 감싸
 * subscription·retry attempt마다 <b>새로</b> 평가한다 — 이미 조립된 Mono 재구독의 공유 상태 오염을 막는
 * lazy fresh publisher 보장이 목적이며, 새 connection/client를 만드는 기능이 아니다.
 *
 * <p>헬퍼는 대상 API의 의미(멱등성·오류 분류)를 알지 못한다 — 어떤 실패를 재시도할지는 전적으로 caller의
 * {@code retryAllowed} predicate가 정한다. 멱등성은 caller 책임이다(이 헬퍼가 만들어 주지 않는다).
 *
 * <p><b>logical call deadline</b>: {@link RetryPolicy#logicalCallTimeout()}은 (pool acquire 포함) 모든
 * wire attempt와 backoff 대기를 합친 전체 상한이다. 만료 시 진행 중 attempt·backoff를 취소하고
 * {@link TimeoutException}을 전달하며, 같은 logical call 안에서 새 retry를 시작하지 않는다.
 *
 * <p>retry 소진 시 Reactor wrapper 예외가 아니라 <b>마지막 원본 failure</b>를 그대로 전달한다.
 * cancellation은 retry 대상이 아니다(구독 취소는 그대로 전파).
 */
public final class RetryHelper {

    private RetryHelper() {
    }

    /**
     * retry policy — {@code maxAttempts}는 <b>최초 호출을 포함</b>한 총 시도 수다.
     * backoff는 exponential({@code firstBackoff} 시작, {@code maxBackoff} 상한)이고 {@code jitter}
     * ({@code [0,1)})로 동시 retry를 분산한다.
     */
    public record RetryPolicy(int maxAttempts, Duration firstBackoff, Duration maxBackoff, double jitter,
            Duration logicalCallTimeout) {

        public RetryPolicy {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1 but was " + maxAttempts);
            }
            requirePositive(firstBackoff, "firstBackoff");
            requirePositive(maxBackoff, "maxBackoff");
            if (firstBackoff.compareTo(maxBackoff) > 0) {
                throw new IllegalArgumentException("firstBackoff must be <= maxBackoff");
            }
            // NaN은 양쪽 비교가 모두 false라 부정형 범위 검사로 차단한다.
            if (!(jitter >= 0.0d && jitter < 1.0d)) {
                throw new IllegalArgumentException("jitter must be in [0, 1) but was " + jitter);
            }
            requirePositive(logicalCallTimeout, "logicalCallTimeout");
        }

        private static void requirePositive(Duration duration, String name) {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(name + " must be a positive duration");
            }
        }
    }

    /**
     * {@code callable}을 최대 {@code policy.maxAttempts()}회 시도한다. {@code retryAllowed}가 true인
     * 실패만 backoff 뒤 재시도하고, false인 실패·cancellation은 그대로 전파한다.
     *
     * @param operation 코드 고정 저카디널리티 작업 이름(사용자·좌표·URL 금지) — deadline 오류 메시지에만 쓴다
     */
    public static <T> Mono<T> callable(String operation, Supplier<? extends Mono<? extends T>> callable,
            RetryPolicy policy, Predicate<? super Throwable> retryAllowed) {
        return Mono.defer(() -> Mono.<T>from(callable.get()))
                .retryWhen(Retry.backoff(policy.maxAttempts() - 1L, policy.firstBackoff())
                        .maxBackoff(policy.maxBackoff())
                        .jitter(policy.jitter())
                        .filter(retryAllowed)
                        // 소진 시 RetryExhaustedException 래핑 대신 마지막 원본 failure type/cause를 보존한다.
                        .onRetryExhaustedThrow((spec, retrySignal) -> retrySignal.failure()))
                // retryWhen 바깥이라 attempt·backoff 전체에 하나의 hard deadline이 걸린다. 만료는 upstream
                // (진행 중 attempt·backoff)을 cancel하고, timeout 오류는 위 filter를 다시 지나지 않는다.
                .timeout(policy.logicalCallTimeout(), Mono.defer(() -> Mono.error(
                        new TimeoutException(operation + " logical call deadline exceeded"))));
    }
}
