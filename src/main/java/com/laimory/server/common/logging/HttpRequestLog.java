package com.laimory.server.common.logging;

import com.laimory.server.common.error.ExceptionType;

/**
 * 요청 완료 access 로그 한 줄의 스키마. {@code StructuredArguments.fields()}로 넘기면
 * JSON encoder에선 각 컴포넌트가 top-level 필드로 전개되고, 텍스트 패턴에선 record toString으로
 * 찍힌다. 필드 추가는 여기 한 곳 — 생성자 호출부가 컴파일 에러로 강제되므로 조용한 유실이 없다.
 *
 * <p>{@code errorCode}는 클라이언트 관점(응답 계약), {@code exceptionType}은 서버 내부 관점
 * (왜 실패했는지 — N:1 매핑의 N쪽)이다. null 필드도 명시적으로 출력한다(현행 스키마 연속성 —
 * ES는 null을 미색인 처리하므로 동작 차이 없음).
 */
record HttpRequestLog(String event, String method, String path, int status, long latencyMs,
                      String errorCode, String exceptionType, String errorDetail) {

    /**
     * 필터가 수집한 원재료로 완료 로그 한 줄을 조립한다. 타입→코드/이름 파생 규칙(없으면 null)은
     * 호출부가 아니라 스키마 곁인 여기가 소유한다.
     */
    static HttpRequestLog of(String method, String path, int status, long latencyMs,
                             ExceptionType type, String errorDetail) {
        return new HttpRequestLog("http_request_completed", method, path, status, latencyMs,
                type != null ? type.errorCode().code() : null,
                type != null ? type.name() : null,
                errorDetail);
    }
}
