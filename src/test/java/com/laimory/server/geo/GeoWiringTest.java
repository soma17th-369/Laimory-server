package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * {@code app.geo.mode} 배선 검증({@link ApplicationContextRunner}) — noop/kakao provider 선택과 fail-fast를
 * 실제 Spring 컨텍스트 기동으로 확인한다.
 *
 * <p>{@link GeocodingService}를 <b>required consumer</b>로 함께 등록하는 것이 핵심이다 — 이게 있어야
 * 매칭 provider 빈이 없을 때(오타·미배선) GeocodingService 주입이 실패해 컨텍스트가 실제로 실패한다.
 * consumer 없이 provider 빈만 등록하면 매칭 빈 0개여도 빈 컨텍스트가 정상 기동해 "오타→실패" 단언이 거짓이 된다.
 * kakao provider 생성자용 {@link RestClient.Builder} 빈도 제공한다.
 */
class GeoWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
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
    void kakaoModeWithBlankKey_failsContext() {
        // kakao provider 생성자의 키 자기검증이 기동을 막는다(fail-fast).
        runner.withPropertyValues("app.geo.mode=kakao", "app.geo.kakao-rest-api-key=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unknownMode_failsContext() {
        // 오타 → noop/kakao 어느 조건도 매칭 안 됨 → MapPlaceProvider 빈 없음 → GeocodingService 주입 실패.
        runner.withPropertyValues("app.geo.mode=kakoo")
                .run(context -> assertThat(context).hasFailed());
    }
}
