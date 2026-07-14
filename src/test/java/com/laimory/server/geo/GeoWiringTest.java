package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.reactive.ClientHttpConnectorAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * {@code app.geo.mode} 배선 검증({@link ApplicationContextRunner}) — noop/kakao provider 선택과 fail-fast를
 * 실제 Spring 컨텍스트 기동으로 확인한다.
 *
 * <p>{@link GeocodingService}를 <b>required consumer</b>로 함께 등록하는 것이 핵심이다 — 이게 있어야
 * 매칭 provider 빈이 없을 때(오타·미배선) GeocodingService 주입이 실패해 컨텍스트가 실제로 실패한다.
 * consumer 없이 provider 빈만 등록하면 매칭 빈 0개여도 빈 컨텍스트가 정상 기동해 "오타→실패" 단언이 거짓이 된다.
 * kakao provider 생성자용 {@link WebClient.Builder} 빈도 제공한다.
 */
class GeoWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(WebClient.Builder.class, WebClient::builder)
            .withUserConfiguration(
                    GeocodingService.class, KakaoMapPlaceProvider.class, NoOpMapPlaceProvider.class);

    @Test
    void noopMode_wiresNoOpProvider() {
        runner.withPropertyValues("app.geo.mode=noop")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(MapPlaceProvider.class)
                        .getBean(MapPlaceProvider.class).isInstanceOf(NoOpMapPlaceProvider.class));
    }

    @Test
    void modeMissing_defaultsToNoOpProvider() {
        // matchIfMissing=true — app.geo.mode 미설정 시 NoOp이 기본.
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(MapPlaceProvider.class)
                .getBean(MapPlaceProvider.class).isInstanceOf(NoOpMapPlaceProvider.class));
    }

    @Test
    void kakaoModeWithKey_wiresKakaoProvider() {
        runner.withPropertyValues("app.geo.mode=kakao", "app.geo.kakao-rest-api-key=test-key")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(MapPlaceProvider.class)
                        .getBean(MapPlaceProvider.class).isInstanceOf(KakaoMapPlaceProvider.class));
    }

    @Test
    void kakaoMode_withAutoConfiguredWebClientBuilder_wiresKakaoProvider() {
        // 수동 withBean이 아니라 Boot WebClientAutoConfiguration이 제공하는 prototype WebClient.Builder가
        // 실제 생성자 주입을 만족하는지 검증 — E2E는 geo.mode=noop이라 Kakao provider를 만들지 않아
        // full-context에서는 이 주입 경로가 커버되지 않는다. (프로퍼티→커넥터 적용 경로는 아래
        // read-timeout 테스트가 별도로 고정한다.)
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebClientAutoConfiguration.class))
                .withUserConfiguration(
                        GeocodingService.class, KakaoMapPlaceProvider.class, NoOpMapPlaceProvider.class)
                .withPropertyValues("app.geo.mode=kakao", "app.geo.kakao-rest-api-key=test-key")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(MapPlaceProvider.class)
                        .getBean(MapPlaceProvider.class).isInstanceOf(KakaoMapPlaceProvider.class));
    }

    @Test
    void kakaoMode_appliesReactiveClientReadTimeout_throughAutoConfiguredConnector() throws IOException {
        // 배선 회귀 가드: 새 SSOT인 spring.http.reactiveclient.read-timeout이 auto-config 커넥터를 거쳐
        // provider의 WebClient에 실제로 적용되는 경로를 고정한다 — provider가 주입 builder 대신 raw
        // WebClient.builder()를 쓰게 되거나 Boot 버전업으로 프로퍼티 이름이 삭는 회귀를 잡는다.
        // 지연 응답(read-timeout 초과)이 전이(io) 실패로 분류돼 콜 단위 재시도(MAX_ATTEMPTS=2) 후
        // retryable=true로 끝나는 것까지 검증한다. 타임아웃이 적용되지 않으면 지연 응답이 그냥 성공해 깨진다.
        MockWebServer server = new MockWebServer();
        try {
            server.start();
            server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setHeadersDelay(2, TimeUnit.SECONDS));
            String baseUrl = server.url("/").toString();
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            WebClientAutoConfiguration.class, ClientHttpConnectorAutoConfiguration.class))
                    .withUserConfiguration(
                            GeocodingService.class, KakaoMapPlaceProvider.class, NoOpMapPlaceProvider.class)
                    .withPropertyValues(
                            "app.geo.mode=kakao",
                            "app.geo.kakao-rest-api-key=test-key",
                            "app.geo.kakao-base-url=" + baseUrl.substring(0, baseUrl.length() - 1),
                            "spring.http.reactiveclient.connect-timeout=1s",
                            "spring.http.reactiveclient.read-timeout=250ms")
                    .run(context -> {
                        MapPlaceProvider provider = context.getBean(MapPlaceProvider.class);
                        assertThatThrownBy(() -> provider.lookup(37.5340, 126.9668).block())
                                .isInstanceOfSatisfying(MapPlaceLookupException.class,
                                        e -> assertThat(e.isRetryable()).isTrue());
                        assertThat(server.getRequestCount()).isEqualTo(2);
                    });
        } finally {
            server.shutdown();
        }
    }

    @Test
    void kakaoModeWithBlankKey_failsContext() {
        // fail-fast 경로 고정: kakao provider 생성자의 키 자기검증(IllegalStateException)이 root cause여야 한다
        // — hasFailed()만 보면 무관한 컨텍스트 오류도 통과하므로 원인 타입·메시지까지 단언.
        runner.withPropertyValues("app.geo.mode=kakao", "app.geo.kakao-rest-api-key=")
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
