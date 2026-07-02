package com.laimory.server.common;

import com.laimory.server.common.logging.TransactionIds;

/**
 * 앱-facing 성공 응답 전용 envelope({@code header} + {@code body}).
 *
 * <p>에러는 다음 마일스톤이라 기존 shape(ErrorResponse)를 그대로 유지한다 — 이 envelope로 감싸지 않는다.
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
}
