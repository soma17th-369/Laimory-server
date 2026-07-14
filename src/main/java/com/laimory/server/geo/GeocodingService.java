package com.laimory.server.geo;

import com.laimory.server.common.logging.TransactionIds;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * 좌표 → 주소·주변 장소명 변환의 domain 진입점이자 <b>blocking 경계</b>. transport(지도 API HTTP·재시도·
 * 응답 파싱)와 어떤 provider를 쓰는지는 알지 못하고 {@link MapPlaceProvider}에 위임한다. provider가 반환한
 * reactive 타입은 여기서 {@code block()}으로 흡수한다 — Reactor가 domain 밖(timeline 계층)으로 새지 않는다.
 * repository 없는 leaf 서비스.
 *
 * <p>구현 선택(noop/kakao)은 {@code app.geo.mode}로 각 {@link MapPlaceProvider} 빈이
 * {@code @ConditionalOnProperty}로 자체 배선한다 — domain은 이 스위치를 알지 못한다. mode 오타로 매칭되는
 * provider 빈이 없으면 이 서비스가 주입받을 빈이 없어 기동 실패한다(암시적 fail-fast).
 *
 * <p><b>실패 전파</b>: provider가 {@link MapPlaceLookupException}을 error 신호로 전달하면(재시도는 이미
 * provider 내부에서 소진됨) {@code block()}이 그 RuntimeException을 <b>원본 그대로</b> 재던진다 — 조용히 빈
 * 결과로 강등하지 않는다(저품질 타임라인 방지). 상위 계층이 이 예외를 draft 생성 실패(502)로 매핑한다.
 */
@Service
public class GeocodingService {

    private final MapPlaceProvider mapPlaceProvider;

    public GeocodingService(MapPlaceProvider mapPlaceProvider) {
        this.mapPlaceProvider = mapPlaceProvider;
    }

    /**
     * 좌표를 enrich 결과로 변환한다. 미연동(noop) 여부는 provider가 결정하고
     * ({@link MapPlaceProvider} 구현), {@link MapPlaceLookupException}은 그대로 전파한다.
     */
    public GeoPlace lookup(double latitude, double longitude) {
        return withTxContext(mapPlaceProvider.lookup(latitude, longitude)).block();
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
