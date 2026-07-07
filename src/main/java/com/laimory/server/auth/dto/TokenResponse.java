package com.laimory.server.auth.dto;

/**
 * 토큰 쌍 응답. accessToken은 API 호출용(Bearer, ~15분), refreshToken은 갱신 전용(~30일, 일회용 회전).
 * refreshToken은 보안 저장소(EncryptedSharedPreferences 등)에 보관할 것 — 앱 계약.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken
) {
}
