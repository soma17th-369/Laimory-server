package com.laimory.server.timeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI 콜백 검증용 one-time Callback-Token 유틸. 원문 토큰은 발급 시 AI에만 전달하고, 서버는 해시만 보관한다.
 *
 * <p>발급(generate)은 256-bit cryptographic random, 보관은 SHA-256 해시, 검증(matches)은 상수시간 비교다.
 */
public final class CallbackTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256-bit

    private CallbackTokens() {
    }

    /** URL/헤더-safe one-time 토큰(Base64 URL-safe, no padding)을 발급한다. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 토큰의 SHA-256 해시(Base64). Redis에는 이 값만 저장한다. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 제시된 토큰의 해시가 저장된 해시와 일치하는지 상수시간 비교한다. 둘 중 하나라도 null이면 false. */
    public static boolean matches(String token, String expectedHash) {
        if (token == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(token).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
