package com.laimory.server.common.error;

import java.util.Locale;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 서버 실패 카탈로그 — 공개 numeric code·HTTP status·access 완료 로그 레벨의 단일 기준.
 *
 * <p>서로 다른 내부 실패 타입이 같은 공개 code를 공유할 수 있다. 예를 들어 refresh 만료와 재사용
 * 탐지는 모두 {@code -2003}이지만 access log level은 각각 INFO/WARN이다. numeric code를 내부 타입으로
 * 되돌리는 전역 lookup은 두지 않는다. 역해석이 필요한 task/callback 경계는 자기 allowlist만 소유한다.
 *
 * <p>사용자 메시지는 code에서 계산한 {@code ERROR_XXXX} message bundle key로 조회한다.
 * 이 key는 외부 응답에 노출하지 않는다.
 *
 * <p>이 레벨은 access 로그 1줄의 레벨만 정한다. 서비스가 남기는 보안 감사·외부 호출 진단
 * 로그는 독립 이벤트로 자기 레벨을 소유한다.
 */
public enum ExceptionType {

    // ── 교차/폴백: 뒤 세 자리는 HTTP status 힌트 ──
    MVC_REQUEST_REJECTED(-400, HttpStatus.BAD_REQUEST, Level.INFO),
    VALIDATION_FAILED(-400, HttpStatus.BAD_REQUEST, Level.INFO),
    APP_CHALLENGE_REJECTED(-400, HttpStatus.BAD_REQUEST, Level.INFO),
    RESOURCE_NOT_FOUND(-404, HttpStatus.NOT_FOUND, Level.INFO),
    METHOD_NOT_ALLOWED(-405, HttpStatus.METHOD_NOT_ALLOWED, Level.INFO),
    UNSUPPORTED_MEDIA_TYPE(-415, HttpStatus.UNSUPPORTED_MEDIA_TYPE, Level.INFO),
    UNEXPECTED_ERROR(-500, HttpStatus.INTERNAL_SERVER_ERROR, Level.ERROR),

    // ── timeline ──
    DRAFT_TASK_NOT_FOUND(-1001, HttpStatus.NOT_FOUND, Level.INFO),
    /**
     * User Memory 갱신 작업 없음 — 만료·이미 종결·중복 도착. draft task와 code·status·message가 같지만
     * 다른 흐름이라 타입을 나눈다. AI는 이 4xx를 재시도 중단 신호로 읽는다.
     */
    SAVE_TASK_NOT_FOUND(-1001, HttpStatus.NOT_FOUND, Level.INFO),
    DRAFT_RESULT_NOT_FOUND(-404, HttpStatus.NOT_FOUND, Level.INFO),
    TASK_TOKEN_MISMATCH(-1002, HttpStatus.UNAUTHORIZED, Level.WARN),
    // -1012: 결번 — 재사용 금지(one-time callback token 소비 계약 제거로 폐기)
    DAILY_RECORD_ALREADY_SAVED(-1003, HttpStatus.CONFLICT, Level.INFO),
    PHOTO_COUNT_EXCEEDED(-1004, HttpStatus.BAD_REQUEST, Level.INFO),
    PHOTO_SIZE_EXCEEDED(-1005, HttpStatus.BAD_REQUEST, Level.INFO),
    // -1006: 결번 — 재사용 금지
    UNSUPPORTED_PHOTO_FORMAT(-1007, HttpStatus.BAD_REQUEST, Level.INFO),
    AI_REPORTED_FAILURE(-1008, HttpStatus.BAD_GATEWAY, Level.WARN),
    AI_DISPATCH_FAILED(-1009, HttpStatus.BAD_GATEWAY, Level.ERROR),
    // -1010: 결번 — 재사용 금지
    DRAFT_TASK_FAILURE_FALLBACK(-1011, HttpStatus.INTERNAL_SERVER_ERROR, Level.ERROR),
    APPEND_NO_NEW_ITEMS(-1013, HttpStatus.CONFLICT, Level.INFO),
    GEOCODING_TRANSIENT_FAILURE(-1014, HttpStatus.BAD_GATEWAY, Level.WARN),
    GEOCODING_PERMANENT_FAILURE(-1015, HttpStatus.BAD_GATEWAY, Level.ERROR),
    // -1016: 결번 — 재사용 금지
    /** 서버간 AI 단계 요청이 task의 현재 상태와 맞지 않음(terminal task 입력 조회·결과 저장, 상충 콜백, 저장 없는 SUCCESS 콜백). */
    DRAFT_TASK_STATE_CONFLICT(-1017, HttpStatus.CONFLICT, Level.WARN),
    /**
     * User Memory 갱신 결과를 적용할 수 없음 — 접수 때 보낸 base 문서가 그 사이 다른 날짜의 갱신으로
     * 교체됐다. 적용하면 그 날짜의 기여가 조용히 사라지므로 결과를 폐기한다.
     */
    SAVE_TASK_STATE_CONFLICT(-1017, HttpStatus.CONFLICT, Level.WARN),
    TIMELINE_EVENT_NOT_FOUND(-404, HttpStatus.NOT_FOUND, Level.INFO),
    DAILY_RECORD_NOT_FOUND(-404, HttpStatus.NOT_FOUND, Level.INFO),
    /** Item 없음·비소유·대상 Event 미연결을 구분 없이 은닉하는 404. */
    TIMELINE_ITEM_NOT_FOUND(-404, HttpStatus.NOT_FOUND, Level.INFO),
    /** Event-Item 연결 해제가 PHOTO 전용인 현재 정책의 non-PHOTO 거절. */
    TIMELINE_ITEM_NOT_PHOTO(-1018, HttpStatus.BAD_REQUEST, Level.INFO),
    /** Event PATCH가 S3 삭제 진행 중인 동일 PHOTO object를 다시 연결하려는 요청. */
    PHOTO_DELETE_IN_PROGRESS(-1019, HttpStatus.CONFLICT, Level.INFO),

    // ── auth ──
    API_AUTHENTICATION_REQUIRED(-2001, HttpStatus.UNAUTHORIZED, Level.INFO),
    APP_CODE_INVALID(-2002, HttpStatus.UNAUTHORIZED, Level.INFO),
    APP_CODE_VERIFIER_MISMATCH(-2002, HttpStatus.UNAUTHORIZED, Level.WARN),
    REFRESH_TOKEN_INVALID(-2003, HttpStatus.UNAUTHORIZED, Level.INFO),
    REFRESH_TOKEN_REUSED(-2003, HttpStatus.UNAUTHORIZED, Level.WARN),
    OAUTH_LOGIN_FAILED(-2004, HttpStatus.UNAUTHORIZED, Level.WARN);

    private final int code;
    private final HttpStatus status;
    private final Level logLevel;

    ExceptionType(int code, HttpStatus status, Level logLevel) {
        this.code = code;
        this.status = status;
        this.logLevel = logLevel;
    }

    /** 클라이언트에 나가는 공개 numeric code. 성공은 이 카탈로그 밖의 0이다. */
    public int code() {
        return code;
    }

    /** BusinessException 응답에 쓰는 HTTP status. */
    public HttpStatus status() {
        return status;
    }

    /** 기존 i18n 번들 lookup 전용 key. 외부 응답에는 노출하지 않는다. */
    public String messageKey() {
        return String.format(Locale.ROOT, "ERROR_%04d", Math.abs(code));
    }

    /** access 완료 로그 한 줄의 레벨 — {@code log.atLevel()}에 직결되는 SLF4J 타입. */
    public Level logLevel() {
        return logLevel;
    }

    /**
     * 프레임워크가 status만 정해주는 예외(MVC 표준 예외·RSE 브리지)를 폴백 타입으로 매핑한다.
     * 열거에 없는 status는 4xx→{@link #MVC_REQUEST_REJECTED}, 그 외→{@link #UNEXPECTED_ERROR}.
     * 응답 HTTP status는 framework 값을 그대로 보존한다 — 여기서 고르는 코드는 근사 폴백일 뿐이라
     * {@code -400}이 HTTP 406과 함께 나갈 수 있다.
     */
    public static ExceptionType fromStatus(HttpStatusCode statusCode) {
        if (statusCode.equals(HttpStatus.NOT_FOUND)) {
            return RESOURCE_NOT_FOUND;
        }
        if (statusCode.equals(HttpStatus.METHOD_NOT_ALLOWED)) {
            return METHOD_NOT_ALLOWED;
        }
        if (statusCode.equals(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
            return UNSUPPORTED_MEDIA_TYPE;
        }
        return statusCode.is4xxClientError() ? MVC_REQUEST_REJECTED : UNEXPECTED_ERROR;
    }
}
