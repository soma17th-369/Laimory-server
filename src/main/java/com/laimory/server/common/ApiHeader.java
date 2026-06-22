package com.laimory.server.common;

/** 성공 응답 공통 헤더: 결과 코드 + 메시지. 성공은 항상 COMMON_0000. */
public record ApiHeader(String code, String message) {
}
