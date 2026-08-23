package com.laimory.server.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.laimory.server.common.logging.TransactionIds;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.publisher.PublisherProbe;

/**
 * 지오코딩 domain의 blocking 경계·병렬 fan-out·outcome materialize 검증. 좌표별 {@link Sinks.One}으로
 * 완료·실패 시점을 테스트가 직접 제어해 sleep 없이 결정론으로 단언한다:
 * <ul>
 *   <li>병렬 구독: concurrency 안에서는 여러 좌표가 동시에 in-flight.
 *   <li>bounded(T28): concurrency를 넘는 좌표는 슬롯이 빌 때까지 미구독.
 *   <li>materialize(D4/T11): 예상된 실패({@link MapPlaceLookupException})는 해당 좌표의
 *       {@link GeoLookupOutcome.Failure}가 되고 <b>다른 in-flight를 취소하지 않는다</b>.
 *   <li>bug fail-fast(D4/T12): 그 외 예외는 materialize하지 않고 그대로 전파한다(in-flight 취소).
 * </ul>
 * 어떤 provider가 배선되는지는 {@link GeoWiringTest}가, 실 카카오 계약은 {@link KakaoMapPlaceProviderTest}가 검증한다.
 */
class GeocodingServiceTest {

    private static final Coordinate C1 = new Coordinate(37.5340, 126.9668);
    private static final Coordinate C2 = new Coordinate(37.4979, 127.0276);
    private static final Coordinate C3 = new Coordinate(37.5445, 127.0557);

    private static final GeoPlace P1 = new GeoPlace("주소1", List.of("장소1"));
    private static final GeoPlace P2 = new GeoPlace("주소2", List.of());
    private static final GeoPlace P3 = new GeoPlace(null, List.of());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** 좌표별 Sinks로 완료·실패 시점을 테스트가 제어하는 provider 스텁. PublisherProbe로 구독·취소를 관찰한다. */
    private static final class ControllableProvider implements MapPlaceProvider {
        private final Map<Coordinate, Sinks.One<GeoPlace>> sinks = new ConcurrentHashMap<>();
        private final Map<Coordinate, PublisherProbe<GeoPlace>> probes = new ConcurrentHashMap<>();

        @Override
        public Mono<GeoPlace> lookup(double latitude, double longitude) {
            return probe(new Coordinate(latitude, longitude)).mono();
        }

        Sinks.One<GeoPlace> sink(Coordinate coordinate) {
            return sinks.computeIfAbsent(coordinate, c -> Sinks.one());
        }

        PublisherProbe<GeoPlace> probe(Coordinate coordinate) {
            return probes.computeIfAbsent(coordinate, c -> PublisherProbe.of(sink(c).asMono()));
        }

        long subscribeCount(Coordinate coordinate) {
            return probe(coordinate).subscribeCount();
        }
    }

    private static Set<Coordinate> orderedSet(Coordinate... coordinates) {
        return new LinkedHashSet<>(List.of(coordinates));
    }

    private static GeoPlace place(GeoLookupOutcome outcome) {
        assertThat(outcome).isInstanceOf(GeoLookupOutcome.Success.class);
        return ((GeoLookupOutcome.Success) outcome).place();
    }

    // ── T1: 빈 입력 — provider 미구독 ──

    @Test
    void lookupAll_returnsEmptyMap_withoutSubscribing_whenNoCoordinates() {
        ControllableProvider provider = new ControllableProvider();
        GeocodingService service = new GeocodingService(provider, 5);

        assertThat(service.lookupAll(orderedSet())).isEmpty();
        assertThat(provider.subscribeCount(C1)).isZero();
    }

    // ── T2: 전 좌표 성공 — 완료 순서 역전에도 모든 실제 값 보존 ──

    @Test
    void lookupAll_collectsAllSuccessOutcomes_evenWhenCompletionOrderReversed() throws Exception {
        ControllableProvider provider = new ControllableProvider();
        GeocodingService service = new GeocodingService(provider, 5);

        CompletableFuture<Map<Coordinate, GeoLookupOutcome>> result =
                CompletableFuture.supplyAsync(() -> service.lookupAll(orderedSet(C1, C2)));

        // 첫 좌표가 완료되기 전에 두 좌표가 모두 구독됨 = 순차가 아니라 병렬.
        await().untilAsserted(() -> {
            assertThat(provider.subscribeCount(C1)).isEqualTo(1);
            assertThat(provider.subscribeCount(C2)).isEqualTo(1);
        });
        // 구독 순서(C1→C2)와 반대로 완료시켜도 좌표별 결과 매핑이 보존된다.
        provider.sink(C2).tryEmitValue(P2);
        provider.sink(C1).tryEmitValue(P1);

        Map<Coordinate, GeoLookupOutcome> outcomes = result.get(5, TimeUnit.SECONDS);
        assertThat(outcomes).hasSize(2);
        assertThat(place(outcomes.get(C1))).isEqualTo(P1);
        assertThat(place(outcomes.get(C2))).isEqualTo(P2);
    }

    // ── T28: bounded concurrency — 슬롯이 빌 때까지 미구독, 누락 없음 ──

    @Test
    void lookupAll_boundsConcurrentSubscriptions_toConfiguredLimit() throws Exception {
        // concurrency=2, 좌표 3개: 앞 2개(입력 순서)는 동시 구독, 3번째는 슬롯이 빌 때까지 미구독(bounded).
        ControllableProvider provider = new ControllableProvider();
        GeocodingService service = new GeocodingService(provider, 2);

        CompletableFuture<Map<Coordinate, GeoLookupOutcome>> result =
                CompletableFuture.supplyAsync(() -> service.lookupAll(orderedSet(C1, C2, C3)));

        await().untilAsserted(() -> {
            assertThat(provider.subscribeCount(C1)).isEqualTo(1);
            assertThat(provider.subscribeCount(C2)).isEqualTo(1);
        });
        assertThat(provider.subscribeCount(C3)).isZero();

        provider.sink(C1).tryEmitValue(P1);
        await().untilAsserted(() -> assertThat(provider.subscribeCount(C3)).isEqualTo(1));
        provider.sink(C2).tryEmitValue(P2);
        provider.sink(C3).tryEmitValue(P3);

        assertThat(result.get(5, TimeUnit.SECONDS)).hasSize(3);
    }

    // ── T11/D4: 예상된 실패 materialize — in-flight 비취소, 전부 집계 ──

    @Test
    void lookupAll_materializesExpectedFailure_withoutCancellingInFlight() throws Exception {
        ControllableProvider provider = new ControllableProvider();
        GeocodingService service = new GeocodingService(provider, 5);
        MapPlaceLookupException transientFailure =
                MapPlaceLookupException.remoteTransient("coord2address http 500", null);

        CompletableFuture<Map<Coordinate, GeoLookupOutcome>> result =
                CompletableFuture.supplyAsync(() -> service.lookupAll(orderedSet(C1, C2)));
        await().untilAsserted(() -> {
            assertThat(provider.subscribeCount(C1)).isEqualTo(1);
            assertThat(provider.subscribeCount(C2)).isEqualTo(1);
        });

        // 한 좌표의 예상된 실패가 다른 in-flight 조회를 취소하지 않고, 실패도 성공도 모두 집계된다.
        provider.sink(C1).tryEmitError(transientFailure);
        assertThat(provider.probe(C2).wasCancelled()).isFalse();
        provider.sink(C2).tryEmitValue(P2);

        Map<Coordinate, GeoLookupOutcome> outcomes = result.get(5, TimeUnit.SECONDS);
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(C1)).isInstanceOfSatisfying(GeoLookupOutcome.Failure.class,
                failure -> assertThat(failure.failure()).isSameAs(transientFailure));
        assertThat(place(outcomes.get(C2))).isEqualTo(P2);
        assertThat(provider.probe(C2).wasCancelled()).isFalse();
    }

    // ── T12/D4: programming error — materialize하지 않고 그대로 전파(in-flight 취소) ──

    @Test
    void lookupAll_propagatesProgrammingError_asIs_andCancelsInFlight() {
        ControllableProvider provider = new ControllableProvider();
        GeocodingService service = new GeocodingService(provider, 5);
        IllegalStateException bug = new IllegalStateException("provider bug");

        CompletableFuture<Map<Coordinate, GeoLookupOutcome>> result =
                CompletableFuture.supplyAsync(() -> service.lookupAll(orderedSet(C1, C2)));
        await().untilAsserted(() -> {
            assertThat(provider.subscribeCount(C1)).isEqualTo(1);
            assertThat(provider.subscribeCount(C2)).isEqualTo(1);
        });

        provider.sink(C1).tryEmitError(bug);

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause().isSameAs(bug);
        await().untilAsserted(() -> assertThat(provider.probe(C2).wasCancelled()).isTrue());
    }

    // ── tx context 전파(회귀) ──

    @Test
    void lookupAll_propagatesTransactionId_intoReactorContext() {
        // 서블릿 스레드 MDC의 transactionId가 Reactor Context로 실려 provider에서 보인다(TxContextLogging의 입력).
        AtomicReference<String> seenTx = new AtomicReference<>();
        MapPlaceProvider provider = (latitude, longitude) -> Mono.deferContextual(ctx -> {
            seenTx.set(ctx.getOrDefault(TransactionIds.MDC_KEY, null));
            return Mono.just(GeoPlace.EMPTY);
        });
        GeocodingService service = new GeocodingService(provider, 5);
        MDC.put(TransactionIds.MDC_KEY, "tx-123");

        service.lookupAll(orderedSet(C1));

        assertThat(seenTx.get()).isEqualTo("tx-123");
    }

    @Test
    void lookupAll_leavesContextEmpty_whenNoTransactionId() {
        // MDC에 tx가 없으면 Context를 건드리지 않는다(Reactor Context는 null 값 거부 — put 자체를 생략).
        AtomicReference<String> seenTx = new AtomicReference<>("sentinel");
        MapPlaceProvider provider = (latitude, longitude) -> Mono.deferContextual(ctx -> {
            seenTx.set(ctx.getOrDefault(TransactionIds.MDC_KEY, null));
            return Mono.just(GeoPlace.EMPTY);
        });
        GeocodingService service = new GeocodingService(provider, 5);

        service.lookupAll(orderedSet(C1));

        assertThat(seenTx.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void constructor_failsFast_whenConcurrencyBelowOne(int concurrency) {
        // flatMap은 concurrency<1이면 요청 처리 중에야 IllegalArgumentException → catch-all 500이 되므로
        // 기동 시점에 자기검증으로 막는다.
        assertThatThrownBy(() -> new GeocodingService(new ControllableProvider(), concurrency))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lookup-concurrency");
    }
}
