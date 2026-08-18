package com.laimory.server.push;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 비로그인 수신거부 credential의 형식 검증·hash·비교. 원문은 저장하지 않으며 예외 메시지·로그에도 남기지
 * 않는다(길이 위반도 길이만 언급하지 않고 형식 위반으로만 표현한다).
 *
 * <p>token은 Android가 installation별로 만든 256-bit 무작위 값이다. 서버는 padding 없는 base64url 43자
 * (= 32 bytes)만 받아들이고 SHA-256 hex hash만 보관한다 — DB 유출로 유효한 수신거부 credential을 얻지
 * 못하게 하기 위해서다.
 */
public final class OptOutTokens {

    /** 32 bytes를 padding 없이 base64url로 인코딩한 길이. */
    static final int TOKEN_LENGTH = 43;
    private static final int TOKEN_BYTES = 32;

    private OptOutTokens() {
    }

    /** 형식이 유효하면 SHA-256 hex hash를, 아니면 예외를 던진다. */
    public static String hash(String token) {
        requireValidFormat(token);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JCA 구현 필수 알고리즘 — 도달 불가 방어.
            throw new IllegalStateException("opt-out token hashing failed", e);
        }
    }

    /**
     * 저장된 hash와 제출 token이 일치하는지 constant-time으로 비교한다. 저장된 hash가 없는 설치
     * (수신거부 수단 없는 legacy)는 항상 false다.
     */
    public static boolean matches(String storedHash, String token) {
        if (storedHash == null || storedHash.isBlank() || token == null) {
            return false;
        }
        String candidate;
        try {
            candidate = hash(token);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII));
    }

    private static void requireValidFormat(String token) {
        if (token == null || token.length() != TOKEN_LENGTH) {
            throw new IllegalArgumentException("optOutToken must be a base64url-encoded 256-bit value");
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("optOutToken must be a base64url-encoded 256-bit value");
        }
        if (decoded.length != TOKEN_BYTES) {
            throw new IllegalArgumentException("optOutToken must be a base64url-encoded 256-bit value");
        }
    }
}
