package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link KakaoGeoProperties} startup validation(T32) — 잘못된 값은 컨텍스트 기동 실패여야 하고, 실패 메시지에
 * property 이름이 있어 운영 override 오류를 바로 찾을 수 있어야 한다. 실제 바인딩 경로
 * ({@code @EnableConfigurationProperties} + relaxed binding + Duration 변환)를 그대로 태운다.
 */
class KakaoGeoPropertiesTest {

    private ApplicationContextRunner kakaoRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebClientAutoConfiguration.class))
                .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withUserConfiguration(GeoMetrics.class, KakaoGeoHttpConfiguration.class)
                .withPropertyValues(GeoWiringTest.GEO_KAKAO_PROPERTIES);
    }

    @Test
    void bindsCheckedInDefaults_successfully() {
        kakaoRunner().run(context -> {
            assertThat(context).hasNotFailed();
            KakaoGeoProperties properties = context.getBean(KakaoGeoProperties.class);
            assertThat(properties.lookupConcurrency()).isEqualTo(20);
            assertThat(properties.http().pool().maxConnections()).isEqualTo(20);
            assertThat(properties.http().pool().pendingAcquireMaxCount()).isEqualTo(200);
            assertThat(properties.retry().maxAttempts()).isEqualTo(2);
            assertThat(properties.circuit().slidingWindowSize()).isEqualTo(20);
        });
    }

    @Test
    void pendingAcquireMaxCountZero_isExplicitlyAllowed() {
        // pending 0(fail-fast 운영 override)은 유일하게 0이 허용되는 count다 — deadline formula의
        // pendingAcquireTimeout 항은 그대로 유지된다(typed 설정 일관성).
        kakaoRunner().withPropertyValues("app.geo.http.pool.pending-acquire-max-count=0")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** 카테고리별 잘못된 값 → 기동 실패 + 메시지에 property 이름(운영자가 바로 찾도록). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "app.geo.lookup-concurrency=0                          | app.geo.lookup-concurrency",
            "app.geo.http.pool.max-connections=0                   | app.geo.http.pool.max-connections",
            "app.geo.http.pool.pending-acquire-max-count=-1        | app.geo.http.pool.pending-acquire-max-count",
            "app.geo.http.pool.pending-acquire-timeout=0s          | app.geo.http.pool.pending-acquire-timeout",
            "app.geo.http.connect-timeout=-1s                      | app.geo.http.connect-timeout",
            "app.geo.http.response-timeout=0s                      | app.geo.http.response-timeout",
            "app.geo.lookup-concurrency=21                         | max-connections",
            "app.geo.http.logical-call-timeout=5s                  | app.geo.http.logical-call-timeout",
            "app.geo.http.pool.max-idle-time=10m                   | max-life-time",
            "app.geo.http.pool.eviction-interval=30s               | eviction-interval",
            "app.geo.retry.max-attempts=3                          | app.geo.retry.max-attempts",
            "app.geo.retry.first-backoff=1s                        | app.geo.retry.first-backoff",
            "app.geo.retry.jitter=1.0                              | app.geo.retry.jitter",
            "app.geo.circuit.sliding-window-size=0                 | app.geo.circuit.sliding-window-size",
            "app.geo.circuit.minimum-number-of-calls=21            | minimum-number-of-calls",
            "app.geo.circuit.failure-rate-threshold=0              | failure-rate-threshold",
            "app.geo.circuit.failure-rate-threshold=101            | failure-rate-threshold",
            "app.geo.circuit.wait-duration-in-open-state=0s        | wait-duration-in-open-state",
            "app.geo.circuit.permitted-calls-in-half-open=0        | permitted-calls-in-half-open",
    })
    void invalidValue_failsContextStartup_withPropertyNameInMessage(String override, String expectedInMessage) {
        kakaoRunner().withPropertyValues(override)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessageChain(context.getStartupFailure()))
                            .contains(expectedInMessage);
                });
    }

    private static String rootMessageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
            if (current.getCause() == current) {
                break;
            }
        }
        return messages.toString();
    }
}
