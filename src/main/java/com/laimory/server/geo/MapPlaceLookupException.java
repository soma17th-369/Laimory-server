package com.laimory.server.geo;

/**
 * {@link MapPlaceProvider} 조회의 예상된 외부 호출·응답 해석·자원 경계 실패. programming error는 이 예외로
 * 감싸지 않고 raw {@code RuntimeException}으로 전파해 catch-all 500으로 드러낸다. provider 내부 retry가
 * 이미 소진된 뒤 전달되므로 상위 계층은 재시도하지 않고 좌표별 최종 outcome으로 materialize한다.
 *
 * <p><b>두 축 분류(D8)</b> — provider 즉시 재시도와 client 나중 재시도 의미를 한 boolean에 섞지 않는다:
 * <ul>
 *   <li>{@link #retryThisCall()} — 같은 logical call 안에서 즉시 wire 재시도가 의미 있는가.
 *       5xx·disconnect·DNS/I/O·connect/response timeout만 true.</li>
 *   <li>{@link #clientMayRetryLater()} — 잠시 뒤 client의 draft 재시도로 회복될 수 있는가.
 *       local pool 거절·circuit open·logical deadline은 true(전이 {@code -1014}),
 *       429·401·403·기타 non-2xx·decode/shape 오류는 false(영구 {@code -1015}).
 *       429의 false는 카카오가 {@code Retry-After}·QPS 복구 계약을 주지 않는 상태에서 자동 재시도를
 *       권고하지 않겠다는 의도다.</li>
 * </ul>
 *
 * <p>{@link Category}는 관측 tag·circuit 계수 판정에 쓴다 — {@link Category#REMOTE}만 circuit breaker
 * 통계에 기록하고 local 압력(pool·deadline)과 open 거절은 remote 건강도를 오염시키지 않도록 ignore한다.
 *
 * <p>⚠️ 메시지엔 좌표·요청 URL·응답 본문을 담지 않는다(위치 민감정보) — endpoint 종류·status 등 서버 값만.
 */
public class MapPlaceLookupException extends RuntimeException {

    /** 실패가 발생한 경계 — 관측 tag(outcome)와 circuit 계수 대상 판정에 쓰는 저카디널리티 분류. */
    public enum Category {
        /** 실제 remote wire 시도가 실패(5xx·I/O·timeout·non-2xx·decode/shape). circuit에 기록한다. */
        REMOTE,
        /** local pool acquire 거절·timeout — remote에 도달하지 않았다. circuit ignore. */
        LOCAL_REJECTED,
        /** logical call deadline 만료 — 진행 중 attempt·backoff가 취소됐다. circuit ignore. */
        LOGICAL_DEADLINE,
        /** circuit open 거절 — upstream 구독 전 차단됐다. circuit ignore. */
        NOT_PERMITTED
    }

    private final Category category;
    private final boolean retryThisCall;
    private final boolean clientMayRetryLater;

    private MapPlaceLookupException(String message, Category category, boolean retryThisCall,
            boolean clientMayRetryLater, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.retryThisCall = retryThisCall;
        this.clientMayRetryLater = clientMayRetryLater;
    }

    /** remote 전이 실패(5xx·disconnect·DNS/I/O·connect/response timeout) — 즉시 재시도·나중 재시도 모두 의미 있음. */
    public static MapPlaceLookupException remoteTransient(String message, Throwable cause) {
        return new MapPlaceLookupException(message, Category.REMOTE, true, true, cause);
    }

    /** remote 영구 실패(429·401·403·기타 non-2xx·decode/shape) — 어떤 재시도도 권고하지 않음. */
    public static MapPlaceLookupException remotePermanent(String message, Throwable cause) {
        return new MapPlaceLookupException(message, Category.REMOTE, false, false, cause);
    }

    /** local pool acquire 거절·timeout — 즉시 재시도는 local 압력만 증폭하므로 금지, 나중 재시도는 가능. */
    public static MapPlaceLookupException localRejected(String message, Throwable cause) {
        return new MapPlaceLookupException(message, Category.LOCAL_REJECTED, false, true, cause);
    }

    /** logical call deadline 만료 — 같은 call의 새 retry 금지, 나중 재시도는 가능. */
    public static MapPlaceLookupException logicalDeadline(String message, Throwable cause) {
        return new MapPlaceLookupException(message, Category.LOGICAL_DEADLINE, false, true, cause);
    }

    /** circuit open 거절 — helper retry 대상이 아니며 나중 재시도는 가능. */
    public static MapPlaceLookupException notPermitted(String message, Throwable cause) {
        return new MapPlaceLookupException(message, Category.NOT_PERMITTED, false, true, cause);
    }

    public Category category() {
        return category;
    }

    /** 같은 logical call 안에서 즉시 wire 재시도를 허용하는가 — RetryHelper predicate가 소비한다. */
    public boolean retryThisCall() {
        return retryThisCall;
    }

    /** client의 나중 draft 재시도로 회복 가능한가 — 전이 {@code -1014}(true)/영구 {@code -1015}(false) 매핑 축. */
    public boolean clientMayRetryLater() {
        return clientMayRetryLater;
    }
}
