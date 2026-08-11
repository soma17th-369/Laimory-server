package com.laimory.server.timeline;

import java.util.regex.Pattern;

/**
 * 클라 원본 데이터 식별자({@code rawId})의 형식 규칙 단일 정의.
 *
 * <p>preflight로 확정한 규칙: <b>version 무관 canonical lowercase UUID</b>. Android는 전 itemType에서
 * {@code randomUUID()}(v4 lowercase)만 발급하고 서버 예시는 v7이라 version은 고정하지 않는다.
 * canonical UUID가 아닌 임의 문자열은 개인정보가 실릴 수 있어 저장·AI dispatch 전에 400으로 거절하고,
 * 허용한 값은 서버 정규화 없이 그대로 저장한다(identity 불변).
 */
public final class RawIds {

    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private RawIds() {
    }

    /** canonical lowercase UUID(8-4-4-4-12, version 무관)인지 검사한다. null·대문자·다른 표기는 거짓. */
    public static boolean isCanonicalUuid(String value) {
        return value != null && CANONICAL_UUID.matcher(value).matches();
    }
}
