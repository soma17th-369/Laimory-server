package com.laimory.server.geo;

/**
 * {@link MapPlaceProvider} 조회 실패. provider가 외부 호출·응답 해석 실패를 <b>전부</b> 이 예외로 감싼다
 * (raw {@code RuntimeException}이 새면 catch-all 500이 되므로). 도메인 계층은 재시도 가능성({@link #isRetryable()})에 따라 이 예외를 502로 매핑한다.
 *
 * <p>{@code retryable}은 provider가 원인으로 분류한다 — 전이적(5xx·타임아웃)이면 {@code true},
 * 영구적(429·401·403·기타 4xx·파싱/shape 오류)이면 {@code false}. provider 내부 재시도가 이미 소진된
 * 뒤 던져지므로, 상위 계층은 재시도하지 않고 그대로 전파한다.
 *
 * <p>⚠️ 메시지엔 좌표·요청 URL·응답 본문을 담지 않는다(위치 민감정보) — endpoint 종류·status 등 서버 값만.
 */
public class MapPlaceLookupException extends RuntimeException {

    private final boolean retryable;

    public MapPlaceLookupException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
