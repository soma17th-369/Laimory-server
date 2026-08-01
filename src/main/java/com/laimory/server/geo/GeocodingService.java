package com.laimory.server.geo;

import com.laimory.server.common.logging.TransactionIds;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * 좌표 → 주소·주변 장소명 변환의 domain 진입점이자 <b>blocking 경계</b>. transport(지도 API HTTP·재시도·
 * 응답 파싱)와 어떤 provider를 쓰는지는 알지 못하고 {@link MapPlaceProvider}에 위임한다. provider의 reactive
 * 타입은 여기서 {@code block()}으로 흡수한다 — Reactor가 domain 밖(timeline 계층)으로 새지 않는다.
 * repository 없는 leaf 서비스.
 *
 * <p><b>병렬 조회</b>: 좌표들을 동시 최대 {@code app.geo.lookup-concurrency}개까지 병렬 구독한다(좌표 내부
 * 2콜은 순차 의존이라 병렬화 축은 좌표 간 fan-out). 구독 시작 순서는 입력 Set의 순회 순서를 따르므로 호출자가
 * {@code LinkedHashSet}을 주면 결정적이고, <b>완료 순서는 비결정</b>이다. process 전체 상한은 전용
 * connection pool({@code app.geo.http.pool.*})이 담당한다.
 *
 * <p>구현 선택(noop/kakao)은 {@code app.geo.mode}로 각 {@link MapPlaceProvider} 빈이
 * {@code @ConditionalOnProperty}로 자체 배선한다 — domain은 이 스위치를 알지 못한다. mode 오타로 매칭되는
 * provider 빈이 없으면 이 서비스가 주입받을 빈이 없어 기동 실패한다(암시적 fail-fast).
 *
 * <p><b>실패 materialize(D4)</b>: 예상된 provider 실패({@link MapPlaceLookupException} — retry는 이미
 * provider 내부에서 소진됨)는 error 신호로 전파하지 않고 해당 좌표의 {@link GeoLookupOutcome.Failure}로
 * 바꿔 <b>나머지 좌표 조회를 계속한다</b> — 부분 실패 허용/거절은 상위 정책(D1/D2)이 전체 aggregate로
 * 판정한다. 그 밖의 programming error는 저품질 성공으로 숨기지 않고 그대로 전파한다(in-flight 취소,
 * 기존 catch-all 500).
 */
@Service
public class GeocodingService {

    private final MapPlaceProvider mapPlaceProvider;
    private final int lookupConcurrency;

    public GeocodingService(
            MapPlaceProvider mapPlaceProvider,
            @Value("${app.geo.lookup-concurrency:20}") int lookupConcurrency) {
        if (lookupConcurrency < 1) {
            // flatMap은 concurrency<1이면 요청 처리 중에야 IllegalArgumentException으로 터져 catch-all 500이
            // 되므로 기동 시 자기검증한다(fail-fast — kakao provider의 키 자기검증과 같은 패턴).
            throw new IllegalStateException(
                    "app.geo.lookup-concurrency must be >= 1 but was " + lookupConcurrency);
        }
        this.mapPlaceProvider = mapPlaceProvider;
        this.lookupConcurrency = lookupConcurrency;
    }

    /**
     * 좌표들을 병렬로 조회해 좌표→최종 outcome map으로 반환한다. 빈 입력이면 provider를 구독하지 않고 빈 map.
     * 허용된 map에는 모든 입력 좌표 key가 있다 — 성공은 실제 {@link GeoPlace}, 예상된 실패는 materialize된
     * {@link GeoLookupOutcome.Failure}다. programming error만 예외로 전파된다.
     */
    public Map<Coordinate, GeoLookupOutcome> lookupAll(Set<Coordinate> coordinates) {
        if (coordinates.isEmpty()) {
            return Map.of();
        }
        return withTxContext(Flux.fromIterable(coordinates)
                .flatMap(coordinate -> mapPlaceProvider.lookup(coordinate.latitude(), coordinate.longitude())
                        .<GeoLookupOutcome>map(GeoLookupOutcome.Success::new)
                        // 예상된 실패만 outcome으로 materialize — 이 좌표의 실패가 다른 in-flight 조회를
                        // 취소하지 않는다. 그 외(코드 결함)는 그대로 전파해 fail-fast를 유지한다.
                        .onErrorResume(MapPlaceLookupException.class,
                                failure -> Mono.just(new GeoLookupOutcome.Failure(failure)))
                        .map(outcome -> Map.entry(coordinate, outcome)), lookupConcurrency)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue))
                .block();
    }

    /**
     * 서블릿 스레드의 transactionId를 Reactor Context로 실어 보낸다 — provider의 signal 로그가 이벤트루프
     * 스레드에서 tx를 복원({@link TxContextLogging})할 수 있도록. 없으면 Context를 건드리지 않는다
     * (Reactor Context는 null 값을 거부한다).
     */
    private static <T> Mono<T> withTxContext(Mono<T> mono) {
        String transactionId = MDC.get(TransactionIds.MDC_KEY);
        return transactionId == null
                ? mono
                : mono.contextWrite(Context.of(TransactionIds.MDC_KEY, transactionId));
    }
}
