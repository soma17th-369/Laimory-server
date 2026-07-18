package com.laimory.server.common.error;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 서버 내부 실패 상황의 카탈로그 — access 완료 로그 레벨의 SSOT.
 *
 * <p>{@link ErrorCode}(클라이언트에게 보여주는 값 — 행동 분기 단위)와 분리된 내부 이름(왜
 * 실패했는지 — 운영 조치 단위)이다. 같은 errorCode로 나가는 상황이라도 심각도가 다를 수 있어
 * <b>N:1로 매핑</b>한다(예: refresh 만료=INFO vs 재사용 탐지=WARN, 둘 다 클라이언트엔
 * {@code ERROR_2003}). 클라이언트는 errorCode만 보고, 정확한 실패 사유는 access 로그의
 * {@code exceptionType} 필드로 확인한다.
 *
 * <p>레벨은 HTTP status와 무관하다 — status는 클라이언트 계약이고 레벨은 서버 관점 심각도라서
 * 독립 축이다(200 응답이어도 서버 관점 ERROR일 수 있다). 그래서 status는 {@link ErrorCode}
 * 쪽에 둔다. message도 여기 두지 않는다 — 사용자 문구는 ErrorCode 이름을 key로 i18n 번들에서
 * 조회한다. 여기(N쪽)에 두면 같은 errorCode의 타입들이 서로 다른 메시지/status를 가질 수 있어,
 * 클라이언트에게 숨기기로 한 내부 구분이 응답으로 유출될 수 있다.
 *
 * <p>이 레벨은 access 로그 1줄의 레벨만 정한다. 서비스가 남기는 보안 감사·외부 호출 진단
 * 로그는 독립 이벤트로 자기 레벨을 소유한다.
 */
public enum ExceptionType {

    // ── 교차/폴백: 전부 ERROR_04xx/0500 계열로 합류 (N:1 — 클라이언트에겐 사유 구분이 무의미) ──
    MVC_REQUEST_REJECTED(ErrorCode.ERROR_0400, Level.INFO),     // MVC 표준 예외 — 미열거 4xx 폴백(406 포함). 정확한 원인은 errorDetail의 예외 클래스명
    VALIDATION_FAILED(ErrorCode.ERROR_0400, Level.INFO),        // 서비스 프로그램적 검증(IllegalArgumentException)
    APP_CHALLENGE_REJECTED(ErrorCode.ERROR_0400, Level.INFO),   // AppChallengeFilter 거절(핸들러 미경유 직접 응답 경로)
    RESOURCE_NOT_FOUND(ErrorCode.ERROR_0404, Level.INFO),       // 봇 스캔·미매핑 경로 — 노이즈
    METHOD_NOT_ALLOWED(ErrorCode.ERROR_0405, Level.INFO),
    UNSUPPORTED_MEDIA_TYPE(ErrorCode.ERROR_0415, Level.INFO),
    UNEXPECTED_ERROR(ErrorCode.ERROR_0500, Level.ERROR),        // catch-all + MVC 처리 5xx 폴백(503 AsyncRequestTimeout 포함)

    // ── timeline ──
    DRAFT_TASK_NOT_FOUND(ErrorCode.ERROR_1001, Level.INFO),         // 만료 포함 — 정상 수명주기
    DRAFT_RESULT_NOT_FOUND(ErrorCode.ERROR_0404, Level.INFO),       // SUCCESS task의 결과 record 없음 — 삭제됨 또는 legacy task(dailyRecordId 부재)
    CALLBACK_TOKEN_MISMATCH(ErrorCode.ERROR_1002, Level.WARN),      // 보안/버그 신호
    DAILY_RECORD_ALREADY_SAVED(ErrorCode.ERROR_1003, Level.INFO),   // 클라 재시도 시나리오
    PHOTO_COUNT_EXCEEDED(ErrorCode.ERROR_1004, Level.INFO),
    PHOTO_SIZE_EXCEEDED(ErrorCode.ERROR_1005, Level.INFO),
    UNSUPPORTED_PHOTO_FORMAT(ErrorCode.ERROR_1007, Level.INFO),     // 사용자 파일 선택으로 유발 — 상세 진단은 서비스의 독립 WARN이 담당
    CALLBACK_TOKEN_ALREADY_USED(ErrorCode.ERROR_1012, Level.WARN),  // 재전송/재사용 — 보안 신호
    APPEND_NO_NEW_ITEMS(ErrorCode.ERROR_1013, Level.INFO),
    GEOCODING_TRANSIENT_FAILURE(ErrorCode.ERROR_1014, Level.WARN),  // 재시도로 해결 가능
    GEOCODING_PERMANENT_FAILURE(ErrorCode.ERROR_1015, Level.ERROR), // 쿼터·키 — 운영 조치 필요
    RECORD_DATE_IN_PROGRESS(ErrorCode.ERROR_1016, Level.INFO),      // 같은 날짜 AI 작업/삭제 진행 중 — 클라 재시도 시나리오
    TIMELINE_EVENT_NOT_FOUND(ErrorCode.ERROR_0404, Level.INFO),     // 편집 대상 event 없음/비소유 — 소유권 은닉(존재 여부 비노출)

    // ── auth: N:1의 실사용례 — 클라이언트엔 "재로그인" 하나, 내부는 일상 vs 공격 신호 ──
    APP_CODE_INVALID(ErrorCode.ERROR_2002, Level.INFO),             // 무효/만료/이미 소비
    APP_CODE_VERIFIER_MISMATCH(ErrorCode.ERROR_2002, Level.WARN),   // 딥링크 탈취 시도 가능성
    REFRESH_TOKEN_INVALID(ErrorCode.ERROR_2003, Level.INFO),        // 무효/만료 — 일상
    REFRESH_TOKEN_REUSED(ErrorCode.ERROR_2003, Level.WARN);         // 재사용 탐지 — 사용자 전체 폐기 동반

    private final ErrorCode errorCode;
    private final Level logLevel;

    ExceptionType(ErrorCode errorCode, Level logLevel) {
        this.errorCode = errorCode;
        this.logLevel = logLevel;
    }

    /** 클라이언트에 나가는 응답 계약 코드 — N:1 매핑의 1쪽. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** access 완료 로그 한 줄의 레벨 — {@code log.atLevel()}에 직결되는 SLF4J 타입. */
    public Level logLevel() {
        return logLevel;
    }

    /**
     * 프레임워크가 status만 정해주는 예외(MVC 표준 예외·RSE 브리지)를 폴백 타입으로 매핑한다.
     * 열거에 없는 status는 4xx→{@link #MVC_REQUEST_REJECTED}, 그 외→{@link #UNEXPECTED_ERROR}.
     * 응답 HTTP status는 framework 값을 그대로 보존한다 — 여기서 고르는 코드는 근사 폴백일 뿐이라
     * {@code ERROR_0400}이 HTTP 406과 함께 나갈 수 있다(errorCode.status()는 BusinessException
     * 경로의 SSOT).
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
