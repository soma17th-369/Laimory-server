package com.laimory.server.push;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 푸시 도메인의 명시적 KST 벽시계 변환 — 예정 시각 계산과 worker claim이 이 함수들만 사용한다.
 *
 * <p>{@code next_due_at}·{@code notification_time}은 {@code Asia/Seoul} 벽시계 계약(offset 없음)이다.
 * JVM 기본 timezone이나 DB {@code NOW()}에 맡기지 않고 캡처한 {@link Instant}를 여기서만 변환한다 —
 * UTC zone의 {@code Clock}을 주입해도 판정이 같은 KST 벽시계로 계산된다({@code TermTimes} 선례).
 */
public final class PushTimes {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private PushTimes() {
    }

    /** 캡처한 절대시각을 KST 벽시계로 변환한다. */
    public static LocalDateTime kstWallClock(Instant instant) {
        return LocalDateTime.ofInstant(instant, KST);
    }

    /** 캡처한 절대시각의 KST 달력 날짜. */
    public static LocalDate kstDate(Instant instant) {
        return kstWallClock(instant).toLocalDate();
    }

}
