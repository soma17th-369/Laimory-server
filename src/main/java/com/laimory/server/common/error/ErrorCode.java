package com.laimory.server.common.error;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * API 에러 코드 카탈로그 — 코드명과 HTTP status의 단일 기준(SSOT).
 *
 * <p>클라이언트 노출 메시지는 여기 두지 않고 {@code messages*.properties} 번들에서
 * 코드명(key)으로 로캘별 조회한다(i18n). 코드명은 한번 배포되면 클라이언트가 분기하는
 * 공개 계약이므로 rename 금지.
 *
 * <p><b>prefix 규칙</b>: 에러 코드는 전부 {@code ERROR_}로 시작한다. {@code COMMON_0000}은 성공 전용이며
 * 이 enum에 없다({@code ApiResponse.success}가 소유) — 클라이언트는 "code가 ERROR_로 시작하면 에러"로 분기한다.
 *
 * <p><b>블록 레지스트리</b> (새 도메인은 1000 블록 단위로 할당):
 * <ul>
 *   <li>{@code ERROR_0xxx} — 교차/폴백 전용. 뒤 세 자리는 HTTP status 힌트(0400=400).
 *       도메인 블록으로 사용 금지.</li>
 *   <li>{@code ERROR_1xxx} — timeline. 1008~1011은 task 실패 분류 — 주 사용처는 폴링 {@code body.error}
 *       (200 응답 안)이며, status는 HTTP 에러로 쓰일 경우의 예비값이다. 1012는 콜백 인증/소비 실패용
 *       401(1002와 같은 성격)로 task 실패 분류가 아니다.</li>
 *   <li>{@code ERROR_2xxx} — (다음 도메인 예약)</li>
 * </ul>
 * 도메인 블록(1xxx~)의 숫자는 HTTP status와 무관하다 — status는 항상 enum 필드가 결정한다.
 */
public enum ErrorCode {

    // ── ERROR_0xxx: 교차/폴백 (뒤 세 자리 = HTTP status 힌트) ──
    ERROR_0400(HttpStatus.BAD_REQUEST),
    ERROR_0404(HttpStatus.NOT_FOUND),
    ERROR_0405(HttpStatus.METHOD_NOT_ALLOWED),
    ERROR_0415(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    ERROR_0500(HttpStatus.INTERNAL_SERVER_ERROR),

    // ── ERROR_1xxx: timeline ──
    ERROR_1001(HttpStatus.NOT_FOUND),      // draft task 없음(만료 포함)
    ERROR_1002(HttpStatus.UNAUTHORIZED),   // 콜백 토큰 불일치
    ERROR_1003(HttpStatus.CONFLICT),       // daily record 이미 SAVED
    ERROR_1004(HttpStatus.BAD_REQUEST),    // 사진 개수 초과 (args: {0}=최대 장수)
    ERROR_1005(HttpStatus.BAD_REQUEST),    // 사진 장당 크기 초과 (args: {0}=최대 MB)
    // ERROR_1006: 결번 — 총합 크기 캡을 배포 전 제거(개수x장당으로 유계, 정상 선택 거절 엣지 방지). 재사용 금지.
    ERROR_1007(HttpStatus.BAD_REQUEST),    // 지원하지 않는 사진 포맷 (args 없음 — 사용자 입력 echo 금지)

    // ── ERROR_1xxx: timeline task 실패 분류 — 폴링 body.error 전용(200 안), status는 예비값 ──
    ERROR_1008(HttpStatus.BAD_GATEWAY),            // AI가 실패 보고(콜백 status=FAILED)
    ERROR_1009(HttpStatus.BAD_GATEWAY),            // AI 서버 호출 실패(dispatch)
    ERROR_1010(HttpStatus.INTERNAL_SERVER_ERROR),  // staging 데이터 누락(복구 불가 상태)
    ERROR_1011(HttpStatus.INTERNAL_SERVER_ERROR),  // finalize 검증/조립 실패

    // ── ERROR_1xxx: timeline 콜백 인증/소비 실패 — 1002와 같은 성격의 401, task 실패 분류 아님 ──
    ERROR_1012(HttpStatus.UNAUTHORIZED);   // 이미 사용된 콜백 토큰(원자적 소비 게이트 거부 — 같은 토큰 재전송 불가)

    /** task 실패 분류 코드 부분집합. {@code markFailed} 멤버십 가드·폴링 read-side 검증이 참조한다. */
    public static final Set<ErrorCode> TASK_FAILURE_CODES =
            Collections.unmodifiableSet(EnumSet.of(ERROR_1008, ERROR_1009, ERROR_1010, ERROR_1011));

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    /** 저장된 문자열이 task 실패 코드명인지 검사한다(폴링 read-side의 과거 raw 값 유출 방어용). */
    public static boolean isTaskFailureCode(String code) {
        return TASK_FAILURE_CODES.stream().anyMatch(c -> c.name().equals(code));
    }

    /** 클라이언트에 노출되는 코드 문자열이자 메시지 번들 key. */
    public String code() {
        return name();
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * 프레임워크가 status만 정해주는 예외(MVC 표준 예외·RSE 브리지)를 폴백 0xxx 코드로 매핑한다.
     * 열거에 없는 status는 4xx→{@link #ERROR_0400}, 그 외→{@link #ERROR_0500}.
     */
    public static ErrorCode fromStatus(HttpStatusCode statusCode) {
        if (statusCode.equals(HttpStatus.NOT_FOUND)) {
            return ERROR_0404;
        }
        if (statusCode.equals(HttpStatus.METHOD_NOT_ALLOWED)) {
            return ERROR_0405;
        }
        if (statusCode.equals(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
            return ERROR_0415;
        }
        return statusCode.is4xxClientError() ? ERROR_0400 : ERROR_0500;
    }
}
