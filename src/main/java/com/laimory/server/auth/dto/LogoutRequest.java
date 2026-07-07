package com.laimory.server.auth.dto;

/** 로그아웃 요청. 해당 refresh만 폐기한다(다른 기기 세션은 유지). */
public record LogoutRequest(
        String refreshToken
) {
}
