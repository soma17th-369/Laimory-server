package com.laimory.server.push;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 푸시 도메인의 명시적 KST 벽시계 변환과 야간 판정 — 예정 시각 계산, worker claim, 발송 직전 야간
 * 재판정이 모두 이 함수들만 사용한다.
 *
 * <p>{@code next_due_at}·{@code notification_time}은 {@code Asia/Seoul} 벽시계 계약(offset 없음)이다.
 * JVM 기본 timezone이나 DB {@code NOW()}에 맡기지 않고 캡처한 {@link Instant}를 여기서만 변환한다 —
 * UTC zone의 {@code Clock}을 주입해도 판정이 같은 KST 벽시계로 계산된다({@code TermTimes} 선례).
 */
public final class PushTimes {

    /** 야간 광고 전송 제한 구간의 시작(포함) — 21:00 정각은 야간이다. */
    public static final LocalTime NIGHT_START = LocalTime.of(21, 0);

    /** 야간 광고 전송 제한 구간의 끝(제외) — 08:00 정각은 주간이다. */
    public static final LocalTime NIGHT_END = LocalTime.of(8, 0);

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

    /** 21:00 이상이거나 08:00 미만이면 야간이다. */
    public static boolean isNight(LocalTime time) {
        return !time.isBefore(NIGHT_START) || time.isBefore(NIGHT_END);
    }

    /** 야간 판정의 시각 축은 벽시계 시:분이다 — 날짜 경계를 넘는 구간도 같은 규칙으로 판정한다. */
    public static boolean isNight(LocalDateTime wallClock) {
        return isNight(wallClock.toLocalTime());
    }
}
