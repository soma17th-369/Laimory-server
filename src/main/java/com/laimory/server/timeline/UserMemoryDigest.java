package com.laimory.server.timeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/**
 * User Memory 문서의 지문. 갱신을 접수할 때 보낸 문서의 지문을 task에 담아 두고, 결과를 적용하기 직전
 * 현재 문서의 지문과 대조한다 — 다르면 그 사이 다른 날짜의 갱신이 문서를 교체했다는 뜻이라 결과를
 * 폐기한다(적용하면 그 날짜의 기여가 조용히 사라진다).
 *
 * <p>"문서 없음"은 {@code null} 지문으로 표현한다 — 없음과 있음의 전이도 대조 대상이다.
 *
 * <p>직렬화 형태가 지문을 정한다. 서버는 문서를 opaque하게 왕복시키므로 저장된 JSON을 그대로 읽어
 * 같은 방식으로 문자열화하면 지문이 재현된다.
 */
public final class UserMemoryDigest {

    private UserMemoryDigest() {
    }

    /** 문서의 SHA-256을 Base64로 반환한다. 문서가 없으면 {@code null}. */
    public static String of(Optional<JsonNode> memory) {
        return memory.map(UserMemoryDigest::of).orElse(null);
    }

    private static String of(JsonNode memory) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(memory.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
