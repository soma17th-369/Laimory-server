package com.laimory.server.timeline;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * draft task의 단계별 토큰 유틸. AI는 입력 조회 → 결과 저장 → 콜백을 서로 다른 토큰으로 호출하며,
 * 서버는 어느 단계의 원문도 저장하지 않고 hash만 보관한다.
 *
 * <p>다음 단계 토큰은 <b>현재 단계 토큰에서 결정적으로 파생</b>한다:
 * <pre>
 *   T1 = 256-bit 난수(dispatch body 전용)
 *   T2 = HMAC-SHA256(key=T1, "timeline-task-token:v1:{taskId}:result")
 *   T3 = HMAC-SHA256(key=T2, "timeline-task-token:v1:{taskId}:callback")
 * </pre>
 * 파생이 필요한 이유: hash만 저장하므로 나중에 T2·T3 <b>원문을 돌려줄 방법이 없다</b>. AI가 제시한 현재
 * 토큰에서 그때 재계산해 응답에 싣는다. 파생이 결정적이라 같은 단계를 몇 번 재시도해도 같은 다음 토큰이
 * 나오며, 응답 유실이 task를 고립시키지 않는다.
 *
 * <p><b>파생 chain은 호출 순서를 강제하지 않는다</b> — T1 보유자는 T2·T3를 스스로 계산할 수 있다. 토큰은
 * "dispatch를 받은 AI임"을 인증할 뿐이며, 결과 저장 없이 SUCCESS 콜백이 들어오는 것을 막는 권위는
 * DB 영수증({@code timeline_ai_result_receipts})이다.
 */
public final class TaskTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256-bit
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DERIVATION_PREFIX = "timeline-task-token:v1:";
    private static final String RESULT_STAGE = ":result";
    private static final String CALLBACK_STAGE = ":callback";

    private TaskTokens() {
    }

    /** 첫 단계(입력 조회) 토큰을 발급한다. URL/헤더-safe Base64(URL-safe, no padding). */
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

    /** 입력 토큰(T1)에서 결과 저장 토큰(T2)을 파생한다. */
    public static String deriveResultToken(String inputToken, String taskId) {
        return derive(inputToken, taskId, RESULT_STAGE);
    }

    /** 결과 저장 토큰(T2)에서 콜백 토큰(T3)을 파생한다. */
    public static String deriveCallbackToken(String resultToken, String taskId) {
        return derive(resultToken, taskId, CALLBACK_STAGE);
    }

    private static String derive(String token, String taskId, String stage) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] derived = mac.doFinal((DERIVATION_PREFIX + taskId + stage).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
