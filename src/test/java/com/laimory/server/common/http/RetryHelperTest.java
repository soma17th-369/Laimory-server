package com.laimory.server.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@link RetryHelper} 계약 검증 — lazy fresh supplier(D12), 유한 attempt·backoff·원본 실패 보존(D13),
 * logical deadline의 cancel·비재시도(D10/T37). backoff·deadline은 {@link StepVerifier#withVirtualTime}으로
 * 실제 대기 없이 결정론으로 단언한다.
 */
class RetryHelperTest {

    private static final RetryHelper.RetryPolicy POLICY = new RetryHelper.RetryPolicy(
            2, Duration.ofMillis(200), Duration.ofMillis(500), 0.0, Duration.ofSeconds(13));

    // ── T17: lazy supplier — 미구독 0회, 구독별 독립 평가 ──

    @Test
    void callable_doesNotEvaluateSupplier_untilSubscribed() {
        AtomicInteger evaluations = new AtomicInteger();

        Mono<String> mono = RetryHelper.callable("op", () -> {
            evaluations.incrementAndGet();
            return Mono.just("ok");
        }, POLICY, e -> true);

        // 조립만으로는 supplier가 평가되지 않는다(lazy) — WebClient publisher를 미리 만들지 않는 보장.
        assertThat(evaluations).hasValue(0);
        assertThat(mono.block()).isEqualTo("ok");
        assertThat(evaluations).hasValue(1);
    }

    @Test
    void callable_evaluatesSupplierFreshly_perSubscriber() {
        AtomicInteger evaluations = new AtomicInteger();
        Mono<Integer> mono = RetryHelper.callable("op",
                () -> Mono.just(evaluations.incrementAndGet()), POLICY, e -> true);

        // 재구독마다 supplier가 새로 평가된다 — subscription-local state 보장(공유 Mono 재사용 아님).
        assertThat(mono.block()).isEqualTo(1);
        assertThat(mono.block()).isEqualTo(2);
    }

    // ── T18: retryable 실패 후 성공 — supplier 총 2회, backoff 뒤 성공 ──

    @Test
    void callable_retriesOnce_afterRetryableFailure_thenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        StepVerifier.withVirtualTime(() -> RetryHelper.callable("op", () -> {
                    if (attempts.incrementAndGet() == 1) {
                        return Mono.error(new IllegalStateException("transient"));
                    }
                    return Mono.just("recovered");
                }, POLICY, e -> true))
                .expectSubscription()
                // 첫 backoff(200ms) 동안에는 결과가 없어야 한다 — 즉시 재시도 아님.
                .expectNoEvent(Duration.ofMillis(199))
                .thenAwait(Duration.ofSeconds(1))
                .expectNext("recovered")
                .verifyComplete();
        assertThat(attempts).hasValue(2);
    }

    // ── T19: retryable 실패 지속 — 총 2회, 마지막 원본 실패 보존 ──

    @Test
    void callable_stopsAtMaxAttempts_andPropagatesLastOriginalFailure() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException last = new IllegalStateException("last");

        StepVerifier.withVirtualTime(() -> RetryHelper.callable("op",
                        () -> Mono.error(attempts.incrementAndGet() == 1 ? first : last),
                        POLICY, e -> true))
                .thenAwait(Duration.ofSeconds(2))
                // Reactor RetryExhausted 래퍼가 아니라 마지막 원본 인스턴스 그대로.
                .expectErrorSatisfies(e -> assertThat(e).isSameAs(last))
                .verify();
        assertThat(attempts).hasValue(2);
    }

    // ── T20: predicate false — retry 0회, 원본 신호 보존 ──

    @Test
    void callable_doesNotRetry_whenPredicateRejectsFailure() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalArgumentException permanent = new IllegalArgumentException("permanent");

        assertThatThrownBy(() -> RetryHelper.callable("op", () -> {
                    attempts.incrementAndGet();
                    return Mono.error(permanent);
                }, POLICY, e -> false).block())
                .isSameAs(permanent);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void callable_withOneMaxAttempt_doesNotRetry_andKeepsOriginalFailure() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("first and last");
        RetryHelper.RetryPolicy noRetry = new RetryHelper.RetryPolicy(
                1, Duration.ofMillis(200), Duration.ofMillis(500), 0.0, Duration.ofSeconds(2));

        assertThatThrownBy(() -> RetryHelper.callable("op", () -> {
                    attempts.incrementAndGet();
                    return Mono.error(failure);
                }, noRetry, e -> true).block())
                .isSameAs(failure);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void callable_doesNotRetry_onCancellation() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();

        var subscription = RetryHelper.callable("op", () -> {
            evaluations.incrementAndGet();
            return Mono.never().doOnCancel(cancels::incrementAndGet);
        }, POLICY, e -> true).subscribe();
        subscription.dispose();

        // 구독 취소는 재시도 사유가 아니다 — upstream cancel만 전파되고 supplier 재평가 없음.
        assertThat(evaluations).hasValue(1);
        assertThat(cancels).hasValue(1);
    }

    // ── T37: logical deadline — attempt/backoff 중 만료 시 cancel, 이후 supplier·retry 0 ──

    @Test
    void callable_cancelsActiveAttempt_whenLogicalDeadlineExpires() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();

        StepVerifier.withVirtualTime(() -> RetryHelper.callable("op", () -> {
                    evaluations.incrementAndGet();
                    return Mono.<String>never().doOnCancel(cancels::incrementAndGet);
                }, POLICY, e -> true))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(13))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(TimeoutException.class)
                        .hasMessageContaining("op"))
                .verify();
        // 진행 중 attempt는 cancel되고 같은 logical call 안에서 새 supplier 평가가 없다.
        assertThat(evaluations).hasValue(1);
        assertThat(cancels).hasValue(1);
    }

    @Test
    void callable_expiresDuringBackoff_withoutStartingNewAttempt() {
        AtomicInteger evaluations = new AtomicInteger();
        // 짧은 deadline(validation은 helper가 아니라 GeoProperties 몫 — policy 자체는 양수만 요구)으로
        // 첫 실패 뒤 backoff(200ms) 중 만료를 재현한다.
        RetryHelper.RetryPolicy shortDeadline = new RetryHelper.RetryPolicy(
                2, Duration.ofMillis(200), Duration.ofMillis(500), 0.0, Duration.ofMillis(100));

        StepVerifier.withVirtualTime(() -> RetryHelper.callable("op", () -> {
                    evaluations.incrementAndGet();
                    return Mono.error(new IllegalStateException("transient"));
                }, shortDeadline, e -> true))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(1))
                .expectError(TimeoutException.class)
                .verify();
        // backoff 대기 중 deadline 만료 — 두 번째 attempt(supplier 평가)가 시작되지 않는다.
        assertThat(evaluations).hasValue(1);
    }

    // ── policy 자기검증 ──

    @Test
    void retryPolicy_rejectsInvalidValues() {
        assertThatThrownBy(() -> new RetryHelper.RetryPolicy(
                0, Duration.ofMillis(1), Duration.ofMillis(2), 0.5, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxAttempts");
        assertThatThrownBy(() -> new RetryHelper.RetryPolicy(
                2, Duration.ofMillis(5), Duration.ofMillis(2), 0.5, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("firstBackoff");
        assertThatThrownBy(() -> new RetryHelper.RetryPolicy(
                2, Duration.ofMillis(1), Duration.ofMillis(2), 1.0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jitter");
        assertThatThrownBy(() -> new RetryHelper.RetryPolicy(
                2, Duration.ofMillis(1), Duration.ofMillis(2), Double.NaN, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("jitter");
        assertThatThrownBy(() -> new RetryHelper.RetryPolicy(
                2, Duration.ofMillis(1), Duration.ofMillis(2), 0.5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("logicalCallTimeout");
    }
}
