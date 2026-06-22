package com.laimory.server.common;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * record_date를 anchor instant에서 서버가 권위 있게 계산한다(정오 경계).
 *
 * <p>논리적 하루는 정오(12:00)에서 다음 날 정오까지 흐른다 — 즉 정오 이전의 활동은 전날에 속한다.
 * 예: local 11:59 → 전날, local 12:00 → 당일.
 *
 * <p>잘못된/알 수 없는 timezone은 {@link IllegalArgumentException}으로 감싸 기존 400 핸들러가 처리하게 한다.
 */
public final class RecordDates {

    private RecordDates() {
    }

    public static LocalDate resolveRecordDate(Instant recordAnchorAt, String recordTimeZone) {
        if (recordAnchorAt == null) {
            throw new IllegalArgumentException("recordAnchorAt must not be null");
        }
        if (recordTimeZone == null) {
            throw new IllegalArgumentException("recordTimeZone must not be null");
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(recordTimeZone);
        } catch (DateTimeException e) { // invalid/unknown zone
            throw new IllegalArgumentException("invalid recordTimeZone: " + recordTimeZone, e);
        }

        LocalDateTime local = LocalDateTime.ofInstant(recordAnchorAt, zone);
        // 정오 경계: 정오 이전이면 전날, 정오(12:00) 이상이면 당일.
        if (local.toLocalTime().isBefore(LocalTime.NOON)) {
            return local.toLocalDate().minusDays(1);
        }
        return local.toLocalDate();
    }
}
