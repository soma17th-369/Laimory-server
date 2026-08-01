package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * {@code app.geo.mode} 배선 검증({@link ApplicationContextRunner}) — noop/kakao provider 선택,
 * kakao 전용 자원 빈(pool·circuit·WebClient)의 조건부 생성/미생성(T33), context 종료 시 pool dispose(D20)와
 * fail-fast를 실제 Spring 컨텍스트 기동으로 확인한다.
 *
 * <p>{@link GeocodingService}를 <b>required consumer</b>로 함께 등록하는 것이 핵심이다 — 이게 있어야
 * 매칭 provider 빈이 없을 때(오타·미배선) GeocodingService 주입이 실패해 컨텍스트가 실제로 실패한다.
 * runner는 {@code application.properties}를 로드하지 않으므로 kakao mode 테스트는 {@code app.geo.*}
 * 전체 값을 명시한다(기본값 자체는 {@code application.properties}가 소유).
 */
class GeoWiringTest {

    /** kakao mode 기동에 필요한 전체 geo property(초기 기본값과 동일 구조). */
    static final String[] GEO_KAKAO_PROPERTIES = {
            "app.geo.mode=kakao",
            "app.geo.kakao-rest-api-key=test-key",
            "app.geo.lookup-concurrency=20",
            "app.geo.http.pool.max-connections=20",
            "app.geo.http.pool.pending-acquire-max-count=20",
            "app.geo.http.pool.pending-acquire-timeout=2s",
            "app.geo.http.connect-timeout=2s",
            "app.geo.http.response-timeout=2s",
            "app.geo.http.logical-call-timeout=13s",
            "app.geo.http.pool.max-idle-time=20s",
            "app.geo.http.pool.max-life-time=5m",
            "app.geo.http.pool.eviction-interval=10s",
            "app.geo.retry.max-attempts=2",
            "app.geo.retry.first-backoff=200ms",
            "app.geo.retry.max-backoff=500ms",
            "app.geo.retry.jitter=0.5",
            "app.geo.circuit.sliding-window-size=20",
            "app.geo.circuit.minimum-number-of-calls=10",
            "app.geo.circuit.failure-rate-threshold=50",
            "app.geo.circuit.wait-duration-in-open-state=30s",
            "app.geo.circuit.permitted-calls-in-half-open=3"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebClientAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(GeoMetrics.class, KakaoGeoHttpConfiguration.class,
                    GeocodingService.class, KakaoMapPlaceProvider.class, NoOpMapPlaceProvider.class);

    @Test
    void noopMode_wiresNoOpProvider_withoutKakaoResources() {
        // T33: noop context는 Kakao pool/WebClient/circuit 빈을 만들지 않는다 — key/설정 독립성.
        runner.withPropertyValues("app.geo.mode=noop")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(MapPlaceProvider.class)
                            .doesNotHaveBean(ConnectionProvider.class)
                            .doesNotHaveBean(CircuitBreaker.class)
                            .doesNotHaveBean(WebClient.class);
                    assertThat(context.getBean(MapPlaceProvider.class)).isInstanceOf(NoOpMapPlaceProvider.class);
                });
    }

    @Test
    void modeMissing_defaultsToNoOpProvider() {
        // matchIfMissing=true — app.geo.mode 미설정 시 NoOp이 기본.
        runner.run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(MapPlaceProvider.class)
                    .doesNotHaveBean(ConnectionProvider.class);
            assertThat(context.getBean(MapPlaceProvider.class)).isInstanceOf(NoOpMapPlaceProvider.class);
        });
    }

    @Test
    void kakaoMode_wiresKakaoProvider_withDedicatedPoolCircuitAndWebClient() {
        runner.withPropertyValues(GEO_KAKAO_PROPERTIES)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(MapPlaceProvider.class)
                            .hasSingleBean(ConnectionProvider.class)
                            .hasSingleBean(CircuitBreaker.class)
                            .hasBean("kakaoGeoWebClient");
                    assertThat(context.getBean(MapPlaceProvider.class)).isInstanceOf(KakaoMapPlaceProvider.class);
                    assertThat(context.getBean(CircuitBreaker.class).getName()).isEqualTo("kakao-local");
                });
    }

    @Test
    void kakaoMode_disposesDedicatedPool_onContextClose() {
        // D20/R6: context 종료가 전용 pool을 dispose해 socket/thread를 회수한다(test·redeploy 자원 회수).
        // isDisposed()는 활성 pool이 없으면 참이라 신뢰할 수 없어 bean definition의 destroy method로 단언한다
        // (실제 dispose 동작 자체는 KakaoGeoResourceBoundaryTest가 활성 connection으로 검증).
        runner.withPropertyValues(GEO_KAKAO_PROPERTIES)
                .run(context -> {
                    assertThat(context).hasSingleBean(ConnectionProvider.class);
                    String destroyMethod = context.getSourceApplicationContext().getBeanFactory()
                            .getBeanDefinition("kakaoGeoConnectionProvider").getDestroyMethodName();
                    assertThat(destroyMethod).isEqualTo("dispose");
                });
    }

    @Test
    void kakaoModeWithBlankKey_failsContext() {
        // fail-fast 경로 고정: 키 자기검증(IllegalStateException)이 root cause여야 한다
        // — hasFailed()만 보면 무관한 컨텍스트 오류도 통과하므로 원인 타입·메시지까지 단언.
        runner.withPropertyValues(GEO_KAKAO_PROPERTIES)
                .withPropertyValues("app.geo.kakao-rest-api-key=")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("KAKAO_REST_API_KEY"));
    }

    @Test
    void unknownMode_failsContext() {
        // fail-fast 경로 고정: 오타 → noop/kakao 어느 조건도 매칭 안 됨 → MapPlaceProvider 빈 부재로
        // GeocodingService 주입 실패가 root cause여야 한다(무관한 오류가 아니라 이 경로임을 못박음).
        runner.withPropertyValues("app.geo.mode=kakoo")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(NoSuchBeanDefinitionException.class)
                        .hasMessageContaining("MapPlaceProvider"));
    }
}
