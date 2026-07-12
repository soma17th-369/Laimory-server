package com.laimory.server.timeline;

/**
 * JWT 요청 인증과 userId 전파 구현 전의 임시 기본값 모음.
 * 인증 주체(SecurityContext 등)에서 userId를 해소하게 되면 제거한다.
 */
public final class TimelineDefaults {

    private TimelineDefaults() {
    }

    /** 인증된 요청 userId 전파 전까지 timeline 흐름이 공유하는 고정 userId. */
    public static final long DEFAULT_USER_ID = 0L;
}
