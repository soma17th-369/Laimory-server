package com.laimory.server.timeline.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * dev 전용 AI 테스트 endpoint의 고정 bearer token 대조 유틸.
 *
 * <p>draft의 회전 {@code TaskTokens}와는 <b>아무 관계가 없다</b> — 여기 token은 단계마다 회전하지도,
 * Redis에 저장되지도 않는 설정 주입 고정값이다. 동기 1회 요청이라 회전·재발급이라는 개념이 없다.
 *
 * <p>비교는 항상 SHA-256 digest끼리 한다. 원문 길이 차이로도 타이밍이 새지 않게 하려는 것이며,
 * 앱은 설정 token의 digest만 보관하고 원문은 어디에도 들고 있지 않는다.
 */
final class TimelineAiTestTokens {

    private TimelineAiTestTokens() {
    }

    /** token의 SHA-256 hash를 Base64로 돌려준다. 보관·비교 대상은 이 값뿐이다. */
    static String digest(String token) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 제시된 원문 token과 보관 중인 digest를 상수 시간으로 대조한다. */
    static boolean matches(String presented, String expectedDigest) {
        if (presented == null || expectedDigest == null) {
            return false;
        }
        return MessageDigest.isEqual(
                digest(presented).getBytes(StandardCharsets.UTF_8),
                expectedDigest.getBytes(StandardCharsets.UTF_8));
    }
}
