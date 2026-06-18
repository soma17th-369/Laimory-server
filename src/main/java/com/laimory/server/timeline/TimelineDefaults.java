package com.laimory.server.timeline;

/**
 * users 도입 전 임시 기본값 모음. 인증이 생기면 제거하고 인증 주체(SecurityContext 등)에서 userId를 해소한다.
 */
public final class TimelineDefaults {

    private TimelineDefaults() {
    }

    /** 사용자 개념이 아직 없어 모든 흐름이 공유하는 고정 userId (plan 모호점 7). */
    public static final long DEFAULT_USER_ID = 0L;
}
