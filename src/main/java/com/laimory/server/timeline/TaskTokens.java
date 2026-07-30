package com.laimory.server.timeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * draft task의 단일 bearer token 유틸. 원문은 dispatch body로 AI에 한 번 전달하고 서버는 Redis task에
 * SHA-256 hash만 저장한다. 입력 조회·결과 저장·콜백은 같은 원문을 매 요청 제시하며, 호출 순서는 Redis
 * {@link TaskStage}가 제한한다.
 */
public final class TaskTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256-bit

    private TaskTokens() {
    }

    /** task token을 발급한다. URL/헤더-safe Base64(URL-safe, no padding). */
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
