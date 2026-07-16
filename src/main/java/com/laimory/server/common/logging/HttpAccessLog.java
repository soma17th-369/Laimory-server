package com.laimory.server.common.logging;

import com.laimory.server.common.error.ExceptionType;

/**
 * 요청 완료 access 로그 한 줄의 스키마. marker로 넘기면 JSON encoder에서 각 컴포넌트가
 * top-level field로 전개된다. 필드 추가는 여기 한 곳 — 생성자 호출부가 컴파일 에러로 강제되므로
 * 조용한 유실이 없다.
 *
 * <p>{@code errorCode}는 클라이언트 관점(응답 계약), {@code exceptionType}은 서버 내부 관점
 * (왜 실패했는지 — N:1 매핑의 N쪽)이다. null 필드도 명시적으로 출력한다(현행 스키마 연속성 —
 * ES는 null을 미색인 처리하므로 동작 차이 없음).
 */
record HttpAccessLog(String event, String method, String path, int status, long latencyMs,
                     String errorCode, String exceptionType, String errorDetail,
                     String clientIp, String requestBody, String responseBody) {

    /**
     * 필터가 수집한 원재료로 완료 로그 한 줄을 조립한다. 타입→코드/이름 파생 규칙(없으면 null)은
     * 호출부가 아니라 스키마 곁인 여기가 소유한다.
     */
    static HttpAccessLog of(String method, String path, int status, long latencyMs,
                            ExceptionType type, String errorDetail, String clientIp,
                            String requestBody, String responseBody) {
        return new HttpAccessLog("http_request_completed", method, path, status, latencyMs,
                type != null ? type.errorCode().code() : null,
                type != null ? type.name() : null,
                errorDetail, clientIp, requestBody, responseBody);
    }
}
