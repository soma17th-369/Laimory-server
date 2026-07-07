package com.laimory.server.auth.dto;

/** 토큰 갱신 요청. 제시한 refresh는 성공 시 회전(무효화)된다 — 응답의 새 refresh로 교체 보관할 것. */
public record TokenRefreshRequest(
        String refreshToken
) {
}
