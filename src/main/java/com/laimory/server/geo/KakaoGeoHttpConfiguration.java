package com.laimory.server.geo;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Kakao 지오코딩 전용 HTTP 자원 배선({@code app.geo.mode=kakao} 한정) — 전용 connection pool,
 * qualified {@link WebClient}, process-wide circuit breaker와 metric binding을 구성한다.
 * noop mode에서는 어떤 빈도 만들지 않아 key/설정 독립성을 보장하고, context 종료 시 pool을 dispose해
 * test/redeploy resource를 회수한다(D20).
 *
 * <p><b>pool 경계(D9~D11)</b>: 한 JVM의 active connection과 pending acquire queue를 모두 유계화한다.
 * shared pool(host당 active 500·pending 1000·acquire 45s)의 숨은 장기 대기를 제거하는 것이 목적이다.
 * pending queue는 한 full-concurrency subscription wave만 짧게 흡수한다 — 겹친 healthy draft를 즉시
 * 실패시키지 않으면서 과부하 대기를 acquire timeout으로 제한한다. HTTP/1.1 keep-alive reuse는 유지하고
 * max idle/life + background eviction이 stale connection을 교체한다.
 *
 * <p><b>global builder 비오염</b>: 전용 connector는 이 {@link WebClient}에만 적용한다 — 다른 소비자가
 * 생기면 auto-config 기본 connector를 그대로 쓴다. 기존 {@code http.client.requests} 관측은
 * auto-configured {@link WebClient.Builder}가 유지한다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.geo.mode", havingValue = "kakao")
@EnableConfigurationProperties(GeoProperties.class)
public class KakaoGeoHttpConfiguration {

    static final String POOL_NAME = "kakao-local";

    /**
     * Kakao 전용 유계 connection pool. {@code metrics(true)}가 Reactor Netty native
     * {@code reactor.netty.connection.provider.*} meter를 pool 이름 {@code kakao-local}로 등록한다.
     * context 종료 시 {@code dispose()}로 socket/thread를 회수한다.
     */
    @Bean(destroyMethod = "dispose")
    ConnectionProvider kakaoGeoConnectionProvider(GeoProperties properties) {
        GeoProperties.Http.Pool pool = properties.http().pool();
        return ConnectionProvider.builder(POOL_NAME)
                .maxConnections(pool.maxConnections())
                .pendingAcquireMaxCount(pool.pendingAcquireMaxCount())
                .pendingAcquireTimeout(pool.pendingAcquireTimeout())
                .maxIdleTime(pool.maxIdleTime())
                .maxLifeTime(pool.maxLifeTime())
                .evictInBackground(pool.evictionInterval())
                .metrics(true)
                .build();
    }

    /**
     * Kakao 전용 {@link WebClient} — 전용 pool·connect/response timeout·숨은 retry 비활성화를 배선한다.
     * {@code disableRetry(true)}는 Reactor Netty가 connection reset 시 조용히 수행하는 내부 재시도를 끈다 —
     * 실제 wire attempt는 {@code RetryHelper}가 단일 계수해야 한다(D13/T35).
     */
    @Bean
    WebClient kakaoGeoWebClient(
            WebClient.Builder webClientBuilder,
            ConnectionProvider kakaoGeoConnectionProvider,
            GeoProperties properties,
            @Value("${app.geo.kakao-rest-api-key}") String kakaoRestApiKey,
            @Value("${app.geo.kakao-base-url:https://dapi.kakao.com}") String kakaoBaseUrl) {
        if (kakaoRestApiKey == null || kakaoRestApiKey.isBlank()) {
            throw new IllegalStateException("KAKAO_REST_API_KEY is required when app.geo.mode=kakao");
        }
        HttpClient httpClient = HttpClient.create(kakaoGeoConnectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) properties.http().connectTimeout().toMillis())
                .responseTimeout(properties.http().responseTimeout())
                .disableRetry(true);
        return webClientBuilder
                .baseUrl(kakaoBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakaoRestApiKey)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * {@code kakao-local} process-wide circuit breaker(D14) — coord2address·keyword의 실제 remote attempt만
     * count-based window로 계수한다. local pool 거절·logical deadline·open 거절·cancellation은
     * {@code ignoreException}으로 통계에서 제외해 local saturation이 remote 건강도를 오염시키지 않는다.
     * 성공은 decode/shape가 유효한 2xx({@code documents=[]} 포함)다.
     *
     * <p>open→half-open automatic transition thread는 끈다 — open wait 경과 뒤 도착한 다음 호출이
     * HALF_OPEN으로 전환한다. slow-call open은 rate threshold 100% + duration threshold를 logical
     * deadline보다 길게 둬 비활성화한다(모든 호출이 deadline 안에 끝나므로 결코 발동하지 않는다).
     */
    @Bean
    CircuitBreaker kakaoGeoCircuitBreaker(GeoProperties properties, MeterRegistry meterRegistry,
            GeoMetrics geoMetrics) {
        GeoProperties.Circuit circuit = properties.circuit();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(circuit.slidingWindowSize())
                .minimumNumberOfCalls(circuit.minimumNumberOfCalls())
                .failureRateThreshold(circuit.failureRateThreshold())
                .waitDurationInOpenState(circuit.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(circuit.permittedCallsInHalfOpen())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .slowCallRateThreshold(100.0f)
                .slowCallDurationThreshold(properties.http().logicalCallTimeout().plusSeconds(1))
                .ignoreException(e -> !(e instanceof MapPlaceLookupException failure
                        && failure.category() == MapPlaceLookupException.Category.REMOTE))
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        CircuitBreaker circuitBreaker = registry.circuitBreaker(POOL_NAME);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.StateTransition transition = event.getStateTransition();
            geoMetrics.countCircuitTransition(
                    transition.getFromState().name(), transition.getToState().name());
            // 상태 이름만 — 좌표·URL·원인 자유 문자열 금지(D18).
            log.warn("kakao-local circuit state transition: {} -> {}",
                    transition.getFromState(), transition.getToState());
        });
        return circuitBreaker;
    }
}
