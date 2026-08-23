package com.laimory.server.terms;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 약관 도메인의 명시적 KST 벽시계 변환 — {@code effective_at} current selection과 {@code accepted_at}
 * 생성이 같은 함수를 사용한다.
 *
 * <p>약관 시각 컬럼은 {@code Asia/Seoul} 벽시계 {@code LocalDateTime} 계약(offset 없음)이다. JVM 기본
 * timezone이나 주입된 {@code Clock}의 zone에 맡기지 않고 캡처한 {@link Instant}를 여기서만 변환한다 —
 * UTC zone의 Clock을 주입해도 판정·기록이 같은 KST 벽시계로 계산된다.
 */
public final class TermTimes {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private TermTimes() {
    }

    /** 캡처한 절대시각을 KST 벽시계로 변환한다. */
    public static LocalDateTime kstWallClock(Instant instant) {
        return LocalDateTime.ofInstant(instant, KST);
    }
}
