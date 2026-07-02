package com.laimory.server.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * API 에러 코드 카탈로그 — 코드명과 HTTP status의 단일 기준(SSOT).
 *
 * <p>클라이언트 노출 메시지는 여기 두지 않고 {@code messages*.properties} 번들에서
 * 코드명(key)으로 로캘별 조회한다(i18n). 코드명은 한번 배포되면 클라이언트가 분기하는
 * 공개 계약이므로 rename 금지.
 *
 * <p><b>블록 레지스트리</b> (새 도메인은 1000 블록 단위로 할당):
 * <ul>
 *   <li>{@code COMMON_4xxx/5xxx} — 교차/폴백 전용. 숫자는 HTTP status 힌트.
 *       도메인 블록으로 사용 금지(HTTP class 오독 방지).</li>
 *   <li>{@code ERROR_1xxx} — timeline</li>
 *   <li>{@code ERROR_2xxx} — (다음 도메인 예약)</li>
 * </ul>
 * 도메인 블록의 숫자는 HTTP status와 무관하다 — status는 항상 enum 필드가 결정한다.
 */
public enum ErrorCode {

    // ── COMMON: 교차/폴백 (숫자 = HTTP 힌트) ──
    COMMON_4000(HttpStatus.BAD_REQUEST),
    COMMON_4040(HttpStatus.NOT_FOUND),
    COMMON_4050(HttpStatus.METHOD_NOT_ALLOWED),
    COMMON_4150(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    COMMON_5000(HttpStatus.INTERNAL_SERVER_ERROR),

    // ── ERROR_1xxx: timeline ──
    ERROR_1001(HttpStatus.NOT_FOUND),      // draft task 없음(만료 포함)
    ERROR_1002(HttpStatus.UNAUTHORIZED),   // 콜백 토큰 불일치
    ERROR_1003(HttpStatus.CONFLICT);       // daily record 이미 SAVED

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    /** 클라이언트에 노출되는 코드 문자열이자 메시지 번들 key. */
    public String code() {
        return name();
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * 프레임워크가 status만 정해주는 예외(MVC 표준 예외·RSE 브리지)를 폴백 COMMON 코드로 매핑한다.
     * 열거에 없는 status는 4xx→{@link #COMMON_4000}, 그 외→{@link #COMMON_5000}.
     */
    public static ErrorCode fromStatus(HttpStatusCode statusCode) {
        if (statusCode.equals(HttpStatus.NOT_FOUND)) {
            return COMMON_4040;
        }
        if (statusCode.equals(HttpStatus.METHOD_NOT_ALLOWED)) {
            return COMMON_4050;
        }
        if (statusCode.equals(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
            return COMMON_4150;
        }
        return statusCode.is4xxClientError() ? COMMON_4000 : COMMON_5000;
    }
}
