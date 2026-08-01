package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * 결정적 loopback 자원·장애 경계 검증(R3~R6·T24~T27·T31) — {@link KakaoGeoHttpConfiguration}의
 * <b>production 배선 그대로</b> 유한성(pool·pending·retry·circuit)과 회복 동작을 확인한다.
 * 통계적 부하/p95를 주장하지 않는다. R3의 active/pending wave는 checked-in 기본값 20/20을 그대로
 * 사용하고, 긴 timeout/lifecycle 시나리오만 결정성을 위해 축소된 시간을 쓴다.
 *
 * <p>report·assertion 메시지에 좌표·주소·URL을 넣지 않는다(D18).
 */
class KakaoGeoResourceBoundaryTest {

    private final KakaoGeoHttpConfiguration configuration = new KakaoGeoHttpConfiguration();

    private MockWebServer server;
    private SimpleMeterRegistry meterRegistry;
    private GeoMetrics geoMetrics;
    private ConnectionProvider pool;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        meterRegistry = new SimpleMeterRegistry();
        // Reactor Netty native pool meter는 global composite registry에 등록된다. 이 테스트 registry를
        // 연결해 active/pending hard bound와 dispose 시 deregistration을 직접 관찰한다.
        Metrics.addRegistry(meterRegistry);
        geoMetrics = new GeoMetrics(meterRegistry);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (pool != null) {
            pool.dispose();
        }
        Metrics.removeRegistry(meterRegistry);
        server.shutdown();
    }

    private static KakaoGeoProperties properties(int lookupConcurrency, int maxConnections, int pendingMax,
            Duration pendingTimeout, Duration maxIdle, Duration maxLife, Duration evictionInterval,
            KakaoGeoProperties.Circuit circuit) {
        // deadline은 validation formula(2×(acquire+connect+response)+maxBackoff)를 만족하는 최솟값 이상으로.
        Duration deadline = pendingTimeout.plus(Duration.ofSeconds(3)).multipliedBy(2).plusSeconds(1);
        return new KakaoGeoProperties(lookupConcurrency,
                new KakaoGeoProperties.Http(Duration.ofSeconds(1), Duration.ofSeconds(2), deadline,
                        new KakaoGeoProperties.Http.Pool(maxConnections, pendingMax, pendingTimeout,
                                maxIdle, maxLife, evictionInterval)),
                new KakaoGeoProperties.Retry(2, Duration.ofMillis(50), Duration.ofMillis(100), 0.0),
                circuit);
    }

    private static KakaoGeoProperties.Circuit quietCircuit() {
        // 자원 경계 테스트에서 circuit이 개입하지 않도록 window를 크게 둔다.
        return new KakaoGeoProperties.Circuit(20, 10, 50, Duration.ofSeconds(30), 3);
    }

    private KakaoMapPlaceProvider provider(KakaoGeoProperties properties) {
        String baseUrl = server.url("/").toString();
        pool = configuration.kakaoGeoConnectionProvider(properties);
        circuitBreaker = configuration.kakaoGeoCircuitBreaker(properties, meterRegistry, geoMetrics);
        WebClient webClient = configuration.kakaoGeoWebClient(
                WebClient.builder(), pool, properties,
                "test-key", baseUrl.substring(0, baseUrl.length() - 1));
        return new KakaoMapPlaceProvider(webClient, circuitBreaker, properties, geoMetrics);
    }

    /** 요청을 latch가 풀릴 때까지 stall시키는 dispatcher — 동시 점유를 테스트가 제어한다. */
    private static final class StallingDispatcher extends Dispatcher {
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger received = new AtomicInteger();

        @Override
        public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            received.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);
            return new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"documents\":[]}");
        }
    }

    // ── R3/T25: 유계 pending — 겹친 wave는 흡수, pending 초과는 즉시 거절, timeout은 typed transient ──

    @Test
    void overlappingWaves_areAbsorbedByBoundedPendingQueue_withoutLocalFailure() {
        // R3 전반부: active 20을 batch A가 점유한 동안 batch B의 acquire 20개는 pending(<=20)에서
        // 기다린다. pending timeout(2s) 안에 release되면 healthy 겹침은 local failure 없이 모두 성공한다.
        StallingDispatcher dispatcher = new StallingDispatcher();
        server.setDispatcher(dispatcher);
        KakaoMapPlaceProvider provider = provider(properties(20, 20, 20, Duration.ofSeconds(2),
                Duration.ofSeconds(20), Duration.ofMinutes(5), Duration.ofSeconds(10), quietCircuit()));

        List<GeoPlace> results = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(40);
        subscribeRange(provider, 0, 20, results, failures, done);
        await().untilAsserted(() -> {
            assertThat(dispatcher.received).hasValue(20);
            assertThat(activeConnections()).isEqualTo(20.0);
        });
        subscribeRange(provider, 20, 40, results, failures, done);
        await().untilAsserted(() -> assertThat(pendingConnections()).isEqualTo(20.0));
        dispatcher.release.countDown();

        await().atMost(Duration.ofSeconds(10)).until(() -> done.getCount() == 0);
        assertThat(failures).isEmpty();
        assertThat(results).hasSize(40);
        await().untilAsserted(() -> {
            assertThat(activeConnections()).isZero();
            assertThat(pendingConnections()).isZero();
        });
    }

    @Test
    void acquireBeyondPositivePendingLimit_failsFast_asTypedLocalTransient() {
        // R3 후반부: checked-in 경계 active 20 + pending 20을 채운 뒤 41번째 acquire는 즉시
        // LOCAL_REJECTED(clientMayRetryLater=true — 나중 재시도 가능)로 실패한다.
        StallingDispatcher dispatcher = new StallingDispatcher();
        server.setDispatcher(dispatcher);
        KakaoMapPlaceProvider provider = provider(properties(20, 20, 20, Duration.ofSeconds(2),
                Duration.ofSeconds(20), Duration.ofMinutes(5), Duration.ofSeconds(10), quietCircuit()));

        List<GeoPlace> completed = new CopyOnWriteArrayList<>();
        List<Throwable> failed = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(40);
        subscribeRange(provider, 0, 20, completed, failed, done);
        await().untilAsserted(() -> assertThat(dispatcher.received).hasValue(20));
        subscribeRange(provider, 20, 40, completed, failed, done);
        await().untilAsserted(() -> assertThat(pendingConnections()).isEqualTo(20.0));

        long startMillis = System.currentTimeMillis();
        assertThatThrownBy(() -> provider.lookup(37.41, 127.41).block())
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> {
                    assertThat(e.category()).isEqualTo(MapPlaceLookupException.Category.LOCAL_REJECTED);
                    assertThat(e.retryThisCall()).isFalse();
                    assertThat(e.clientMayRetryLater()).isTrue();
                });
        // fail-fast — pending timeout(2s)을 기다리지 않는다.
        assertThat(System.currentTimeMillis() - startMillis).isLessThan(1_500);
        // 41번째는 wire에 도달하지 않았다(서버 수신 20건 그대로, 다음 20건은 pending).
        assertThat(dispatcher.received).hasValue(20);
        // local 거절은 circuit 통계에 기록되지 않는다(D14 ignore).
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        dispatcher.release.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() -> done.getCount() == 0);
        assertThat(completed).hasSize(40);
        assertThat(failed).isEmpty();
        await().untilAsserted(() -> {
            assertThat(activeConnections()).isZero();
            assertThat(pendingConnections()).isZero();
        });
    }

    @Test
    void pendingAcquireTimeout_becomesTypedLocalTransient_andPoolRecoversAfterRelease() {
        // pending에 들어갔지만 timeout(500ms)까지 connection이 반환되지 않으면 LOCAL_REJECTED로 끝난다.
        // release 뒤 pool은 회복돼 새 lookup이 성공한다(T25 종료 조건: active/pending 회수).
        StallingDispatcher dispatcher = new StallingDispatcher();
        server.setDispatcher(dispatcher);
        KakaoMapPlaceProvider provider = provider(properties(1, 1, 1, Duration.ofMillis(500),
                Duration.ofSeconds(20), Duration.ofMinutes(5), Duration.ofSeconds(10), quietCircuit()));

        provider.lookup(37.01, 127.01).subscribe(place -> { }, failure -> { });
        await().untilAsserted(() -> assertThat(dispatcher.received.get()).isEqualTo(1));

        assertThatThrownBy(() -> provider.lookup(37.02, 127.02).block())
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e ->
                        assertThat(e.category()).isEqualTo(MapPlaceLookupException.Category.LOCAL_REJECTED));

        dispatcher.release.countDown();
        // 점유가 풀린 뒤에는 같은 pool로 새 lookup이 성공한다 — 자원 누수·영구 고착 없음.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(provider.lookup(37.03, 127.03).block().places()).isEmpty());
    }

    // ── R5/T23/T24/T31: circuit — 경계·open 차단·half-open 회복 ──

    private static KakaoGeoProperties.Circuit smallCircuit(Duration openWait) {
        // window 4·minimum 2·rate 50% — 2연속 실패로 open. half-open probe는 1개.
        return new KakaoGeoProperties.Circuit(4, 2, 50, openWait, 1);
    }

    private void enqueueJson(String body) {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
    }

    /** open wait(300ms)이 확실히 지나도록 대기 — half-open 전이는 wait 경과 뒤 "다음 호출"이 만든다. */
    private static void sleepBeyondOpenWait() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void circuit_staysClosedBelowMinimumCalls_thenOpensAtThreshold_andBlocksWire() {
        KakaoMapPlaceProvider provider = provider(properties(2, 2, 2, Duration.ofSeconds(2),
                Duration.ofSeconds(20), Duration.ofMinutes(5), Duration.ofSeconds(10),
                smallCircuit(Duration.ofSeconds(30))));

        // T23: minimum calls(2) 미만에서는 실패율 100%여도 열리지 않는다.
        server.enqueue(new MockResponse().setResponseCode(401));
        assertThatThrownBy(() -> provider.lookup(37.01, 127.01).block())
                .isInstanceOf(MapPlaceLookupException.class);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 두 번째 remote 실패로 minimum 도달 + 실패율 100% ≥ 50% → OPEN.
        server.enqueue(new MockResponse().setResponseCode(401));
        assertThatThrownBy(() -> provider.lookup(37.02, 127.02).block())
                .isInstanceOf(MapPlaceLookupException.class);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // T24: open이면 wire 구독·helper retry 없이 NOT_PERMITTED로 즉시 끝난다(서버 수신 2건 그대로).
        assertThatThrownBy(() -> provider.lookup(37.03, 127.03).block())
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e -> {
                    assertThat(e.category()).isEqualTo(MapPlaceLookupException.Category.NOT_PERMITTED);
                    assertThat(e.retryThisCall()).isFalse();
                    assertThat(e.clientMayRetryLater()).isTrue();
                });
        assertThat(server.getRequestCount()).isEqualTo(2);
        // 상태 전이 counter — CLOSED→OPEN 1회.
        var transitions = meterRegistry.find("laimory.geo.circuit.transitions")
                .tag("from", "CLOSED").tag("to", "OPEN").counter();
        assertThat(transitions).isNotNull();
        assertThat(transitions.count()).isEqualTo(1);
    }

    @Test
    void circuit_halfOpenProbeSuccess_closes_afterOpenWait() {
        // T31/R5·L8 회복 경로: open wait 경과 뒤 도착한 다음 호출이 HALF_OPEN 전환(automatic transition 없음)
        // — probe 성공이면 CLOSED로 복귀한다.
        KakaoMapPlaceProvider provider = provider(properties(2, 2, 2, Duration.ofSeconds(2),
                Duration.ofSeconds(20), Duration.ofMinutes(5), Duration.ofSeconds(10),
                smallCircuit(Duration.ofMillis(300))));

        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(401));
        assertThatThrownBy(() -> provider.lookup(37.01, 127.01).block())
                .isInstanceOf(MapPlaceLookupException.class);
        assertThatThrownBy(() -> provider.lookup(37.02, 127.02).block())
                .isInstanceOf(MapPlaceLookupException.class);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // open wait 동안에는 계속 차단된다(automatic half-open transition 없음).
        assertThatThrownBy(() -> provider.lookup(37.03, 127.03).block())
                .isInstanceOfSatisfying(MapPlaceLookupException.class, e ->
                        assertThat(e.category()).isEqualTo(MapPlaceLookupException.Category.NOT_PERMITTED));

        enqueueJson("{\"documents\":[]}");
        // wait(300ms) 경과 뒤 첫 호출이 HALF_OPEN probe가 되고(automatic transition 없음), 성공하면 CLOSED.
        // untilAsserted는 비-assertion 예외(open 거절)를 재시도하지 않으므로 wait 경과를 sleep으로 보장한다.
        sleepBeyondOpenWait();
        GeoPlace place = provider.lookup(37.04, 127.04).block();
        assertThat(place.address()).isNull();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void circuit_halfOpenProbeFailure_reopens() {
        KakaoMapPlaceProvider provider = provider(properties(2, 2, 2, Duration.ofSeconds(2),
                Duration.ofSeconds(20), Duration.ofMinutes(5), Duration.ofSeconds(10),
                smallCircuit(Duration.ofMillis(300))));

        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(401));
        assertThatThrownBy(() -> provider.lookup(37.01, 127.01).block())
                .isInstanceOf(MapPlaceLookupException.class);
        assertThatThrownBy(() -> provider.lookup(37.02, 127.02).block())
                .isInstanceOf(MapPlaceLookupException.class);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // probe(halfOpen permitted 1)가 다시 실패하면 OPEN으로 복귀한다. probe의 500은 retryThisCall이지만
        // 재시도 시점엔 circuit이 이미 재개방돼 NOT_PERMITTED로 끝날 수 있다 — 상태·wire 소비로 단언한다.
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        int wireBeforeProbe = server.getRequestCount();
        sleepBeyondOpenWait();
        assertThatThrownBy(() -> provider.lookup(37.04, 127.04).block())
                .isInstanceOf(MapPlaceLookupException.class);
        // wait 경과 뒤라 probe가 실제 wire를 소비했어야 한다(그 전에는 차단돼 wire 0).
        assertThat(server.getRequestCount()).isGreaterThan(wireBeforeProbe);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        var reopened = meterRegistry.find("laimory.geo.circuit.transitions")
                .tag("from", "HALF_OPEN").tag("to", "OPEN").counter();
        assertThat(reopened).isNotNull();
        assertThat(reopened.count()).isGreaterThanOrEqualTo(1);
    }

    // ── R6/T27: connection lifecycle — keep-alive reuse, remote close 뒤 새 연결, idle eviction, dispose ──

    @Test
    void keepAlive_reusesConnection_thenReplacesAfterRemoteClose_andIdleEviction() throws Exception {
        KakaoMapPlaceProvider provider = provider(properties(2, 2, 2, Duration.ofSeconds(2),
                Duration.ofMillis(300), Duration.ofSeconds(30), Duration.ofMillis(100), quietCircuit()));

        // 1) 순차 정상 — 같은 connection 재사용(HTTP/1.1 keep-alive).
        enqueueJson("{\"documents\":[]}");
        enqueueJson("{\"documents\":[]}");
        provider.lookup(37.01, 127.01).block();
        provider.lookup(37.02, 127.02).block();
        RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest second = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(first.getSequenceNumber()).isZero();
        // sequenceNumber>0 = 같은 socket의 두 번째 요청(reuse).
        assertThat(second.getSequenceNumber()).isEqualTo(1);

        // 2) remote close 뒤에는 새 connection으로 계속된다.
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"documents\":[]}").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
        provider.lookup(37.03, 127.03).block();
        server.takeRequest(2, TimeUnit.SECONDS);
        enqueueJson("{\"documents\":[]}");
        provider.lookup(37.04, 127.04).block();
        RecordedRequest afterClose = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(afterClose.getSequenceNumber()).isZero();

        // 3) max-idle(300ms) 경과 + background eviction(100ms) → 다음 요청은 새 connection.
        Thread.sleep(800);
        enqueueJson("{\"documents\":[]}");
        provider.lookup(37.05, 127.05).block();
        RecordedRequest afterIdle = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(afterIdle.getSequenceNumber()).isZero();

        // 4) dispose — 자원 회수(T33의 context 종료 dispose는 GeoWiringTest가 검증).
        pool.dispose();
        assertThat(pool.isDisposed()).isTrue();
        await().untilAsserted(() -> assertThat(meterRegistry
                .find("reactor.netty.connection.provider.total.connections")
                .tag("name", KakaoGeoHttpConfiguration.POOL_NAME)
                .gauges()).isEmpty());
    }

    private double pendingConnections() {
        return meterRegistry.find("reactor.netty.connection.provider.pending.connections")
                .tag("name", KakaoGeoHttpConfiguration.POOL_NAME)
                .gauges().stream()
                .mapToDouble(gauge -> gauge.value())
                .sum();
    }

    private double activeConnections() {
        return meterRegistry.find("reactor.netty.connection.provider.active.connections")
                .tag("name", KakaoGeoHttpConfiguration.POOL_NAME)
                .gauges().stream()
                .mapToDouble(gauge -> gauge.value())
                .sum();
    }

    private static void subscribeRange(KakaoMapPlaceProvider provider, int startInclusive, int endExclusive,
            List<GeoPlace> results, List<Throwable> failures, CountDownLatch done) {
        IntStream.range(startInclusive, endExclusive)
                .mapToObj(index -> provider.lookup(37.0 + index / 1_000.0, 127.0 + index / 1_000.0))
                .forEach(lookup -> lookup.subscribe(place -> {
                    results.add(place);
                    done.countDown();
                }, failure -> {
                    failures.add(failure);
                    done.countDown();
                }));
    }
}
