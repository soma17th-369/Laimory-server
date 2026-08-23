package com.laimory.server.common;

/**
 * 앱-facing 응답 공통 envelope({@code header} + {@code body}) — 성공·에러 모두 이 shape로 나간다.
 *
 * <p>성공은 {@link #success}({@code 0}, 빈 메시지)이며 반환할 결과가 없는 성공도 {@code body=null}인 envelope를
 * 유지한다. 에러는 {@link #error}(에러 코드 + 로캘 메시지, {@code body=null})이고 변환은
 * {@code GlobalExceptionHandler}가 전담한다.
 *
 * <p>FAILED 폴링도 HTTP 200 + code 0이고, 실제 상태는 {@code body.status}에 담긴다(헤더로 매핑 금지).
 *
 * @param <T> 응답 본문 타입
 */
public record ApiResponse<T>(ApiHeader header, T body) {

    /** 성공 응답을 만든다. 헤더는 항상 {@code 0}/빈 문자열이다. */
    public static <T> ApiResponse<T> success(T body) {
        return new ApiResponse<>(new ApiHeader(0, ""), body);
    }

    /** 에러 응답을 만든다. 코드·메시지는 header에, body는 null. */
    public static ApiResponse<Void> error(int code, String message) {
        return new ApiResponse<>(new ApiHeader(code, message), null);
    }
}
