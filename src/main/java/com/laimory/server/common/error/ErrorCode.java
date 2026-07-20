package com.laimory.server.common.error;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * API 에러 코드 카탈로그 — 클라이언트에게 보여주는 값(코드명·HTTP status)의 단일 기준(SSOT).
 *
 * <p>서버 내부에서 예외를 던질 때는 이 enum이 아니라 {@link ExceptionType}을 고른다 — 내부 실패
 * 사유와 로그 레벨은 그쪽 소유이고, 여기와 N:1로 매핑된다. 이 enum은 응답 계약과 데이터 어휘
 * (폴링 {@code body.error}·핸드오프 링크 파라미터)의 기준으로만 쓴다.
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
 *   <li>{@code ERROR_2xxx} — auth(인증·토큰). 2001은 /a/api 인증 필요(Security EntryPoint 전용).</li>
 *   <li>{@code ERROR_3xxx} — (다음 도메인 예약)</li>
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
    ERROR_1012(HttpStatus.UNAUTHORIZED),   // 이미 사용된 콜백 토큰(원자적 소비 게이트 거부 — 같은 토큰 재전송 불가)

    // ── ERROR_1xxx: timeline append 도메인 거절 ──
    ERROR_1013(HttpStatus.CONFLICT),       // append 요청의 모든 item이 이미 타임라인에 저장됨(추가할 신규 item 없음)

    // ── ERROR_1xxx: timeline 지오코딩(지도 API) 호출 실패 — 동기 POST 502(폴링 read-side 아님 → TASK_FAILURE_CODES 미포함) ──
    // 재시도 가능성으로 코드를 분리한다 — 클라가 재시도 UX를 분기(1014=잠시 후 재시도, 1015=지속 시 문의).
    // provider가 원인을 retryable로 분류하고(전이 5xx·타임아웃=true / 영구 429·401·403·4xx·파싱=false) enrichment이 해당 코드로 매핑한다.
    ERROR_1014(HttpStatus.BAD_GATEWAY),    // 전이적 실패(5xx·타임아웃, 콜 단위 재시도 소진) — 재시도로 해결될 수 있음
    ERROR_1015(HttpStatus.BAD_GATEWAY),    // 영구적 실패(429 쿼터·401/403 키·기타 4xx·파싱/shape) — 즉시 재시도는 무의미

    // ── ERROR_1xxx: timeline 날짜 동시성 guard — 동기 409 전용(task 실패 분류 아님 → TASK_FAILURE_CODES 미포함) ──
    ERROR_1016(HttpStatus.CONFLICT),       // 같은 날짜의 AI 작업/삭제가 진행 중(date guard 선점 실패) — 잠시 후 재시도

    // ── ERROR_1xxx: timeline 사진 배치 삭제 실패 — 동기 502 전용(task 실패 분류 아님 → TASK_FAILURE_CODES 미포함) ──
    ERROR_1017(HttpStatus.BAD_GATEWAY),    // 삭제의 S3 배치 삭제 실패(SDK 예외·객체별 error) — DB 미삭제 보존, 클라 재시도로 수렴

    // ── ERROR_2xxx: auth(인증·토큰) ──
    ERROR_2001(HttpStatus.UNAUTHORIZED),   // /a/api 인증 필요(Bearer 부재/무효/만료 — 사유 비구분) → 재로그인
    ERROR_2002(HttpStatus.UNAUTHORIZED),   // app_code 교환 실패(무효/만료/이미 소비/verifier 불일치) → 재로그인
    ERROR_2003(HttpStatus.UNAUTHORIZED),   // refresh 실패(무효/만료/철회/재사용 탐지 — 탐지 시 사용자 전체 폐기) → 재로그인
    ERROR_2004(HttpStatus.UNAUTHORIZED);   // 소셜 로그인 핸드셰이크 실패 — HTTP 응답이 아니라 핸드오프 링크 ?error= 파라미터로 전달(status는 예비값)

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
}
