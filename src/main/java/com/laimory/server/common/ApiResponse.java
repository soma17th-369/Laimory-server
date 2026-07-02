package com.laimory.server.common;

import com.laimory.server.common.logging.TransactionIds;

/**
 * 앱-facing 응답 공통 envelope({@code header} + {@code body}) — 성공·에러 모두 이 shape로 나간다.
 *
 * <p>성공은 {@link #success}(COMMON_0000), 에러는 {@link #error}(에러 코드 + 로캘 메시지, {@code body=null}).
 * 에러 변환은 {@code GlobalExceptionHandler}가 전담한다.
 *
 * <p>FAILED 폴링도 HTTP 200 + COMMON_0000이고, 실제 상태는 {@code body.status}에 담긴다(헤더로 매핑 금지).
 *
 * @param <T> 응답 본문 타입
 */
public record ApiResponse<T>(ApiHeader header, T body) {

    /** 성공 응답을 만든다. 헤더는 항상 COMMON_0000/"success" + 현재 요청의 transactionId. */
    public static <T> ApiResponse<T> success(T body) {
        return new ApiResponse<>(new ApiHeader("COMMON_0000", "success", TransactionIds.current()), body);
    }

    /** 에러 응답을 만든다. 코드·메시지는 header에, body는 null. */
    public static ApiResponse<Void> error(String code, String message) {
        return new ApiResponse<>(new ApiHeader(code, message, TransactionIds.current()), null);
    }
}
