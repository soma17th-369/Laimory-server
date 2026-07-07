package com.laimory.server.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 자체 인증 토큰(app_code·refresh token) 원문 생성/해시 유틸. 원문은 클라이언트에만 전달하고
 * 서버(DB·Redis)에는 해시만 보관한다({@code timeline.CallbackTokens}와 같은 원칙 — 해시 인코딩이
 * hex(DDL {@code CHAR(64)})라 별도 유틸로 둔다).
 */
public final class AuthTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256-bit

    private AuthTokens() {
    }

    /** URL-safe 랜덤 토큰(Base64 URL-safe, no padding, 256-bit)을 발급한다. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 토큰의 SHA-256 해시(hex 64자). refresh_tokens.token_hash·app_code Redis 키에 저장한다. */
    public static String sha256Hex(String raw) {
        return HexFormat.of().formatHex(sha256(raw));
    }

    /**
     * 핸드오프 PKCE의 challenge 계산: {@code base64url(sha256(verifier))}, 패딩 없음.
     * 앱이 로그인 시작 시 보내는 app_challenge와 같은 규칙이다.
     */
    public static String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
    }

    /** 제시된 verifier가 저장된 challenge와 일치하는지 상수시간 비교한다. 둘 중 하나라도 null이면 false. */
    public static boolean matchesChallenge(String verifier, String expectedChallenge) {
        if (verifier == null || expectedChallenge == null) {
            return false;
        }
        return MessageDigest.isEqual(
                challenge(verifier).getBytes(StandardCharsets.UTF_8),
                expectedChallenge.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
