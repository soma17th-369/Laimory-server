package com.laimory.server.user;

import java.util.Locale;

/** 소셜 로그인 제공자. users 유일성의 절반((provider, provider_user_id))을 구성한다. */
public enum Provider {
    GOOGLE,
    KAKAO;

    /**
     * Spring Security registrationId(설정의 {@code google}/{@code kakao})를 매핑한다.
     * registrationId는 우리 설정 유래라 불일치는 설정 버그 — 내부 불변식 위반으로 처리한다.
     */
    public static Provider fromRegistrationId(String registrationId) {
        try {
            return valueOf(registrationId.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("지원하지 않는 OAuth registrationId: " + registrationId, e);
        }
    }
}
