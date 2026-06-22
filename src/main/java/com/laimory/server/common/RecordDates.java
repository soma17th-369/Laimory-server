package com.laimory.server.common;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * record_date를 클라가 보낸 벽시계 anchor에서 서버가 권위 있게 계산한다(정오 경계).
 *
 * <p>클라는 자기 zone의 벽시계 {@code LocalDateTime}을 보낸다(item 시간들과 동일 표현). 따라서 날짜 계산엔
 * timezone 변환이 필요 없다 — 논리적 하루는 정오(12:00)에서 다음 날 정오까지 흐르므로, 벽시계 시각이 정오 이전이면 전날에 속한다.
 * 예: local 11:59 → 전날, local 12:00 → 당일.
 *
 * <p>{@code recordTimeZone}은 날짜 계산엔 안 쓰이고 저장(역산: 저장된 벽시계의 절대시각 해석)용으로만 보존되므로,
 * 유효성만 {@link #requireValidTimeZone(String)}로 검증한다(잘못된 zone → {@link IllegalArgumentException} → 400).
 */
public final class RecordDates {

    private RecordDates() {
    }

    /** 벽시계 anchor에 정오 경계를 적용해 record_date를 도출한다(zone 불필요). */
    public static LocalDate resolveRecordDate(LocalDateTime recordAnchorAt) {
        if (recordAnchorAt == null) {
            throw new IllegalArgumentException("recordAnchorAt must not be null");
        }
        // 정오 경계: 정오 이전이면 전날, 정오(12:00) 이상이면 당일.
        if (recordAnchorAt.toLocalTime().isBefore(LocalTime.NOON)) {
            return recordAnchorAt.toLocalDate().minusDays(1);
        }
        return recordAnchorAt.toLocalDate();
    }

    /** 저장·역산에 쓸 timezone 문자열이 유효한 zone인지 검증한다(잘못되면 IllegalArgumentException으로 래핑 → 400). */
    public static void requireValidTimeZone(String recordTimeZone) {
        if (recordTimeZone == null) {
            throw new IllegalArgumentException("recordTimeZone must not be null");
        }
        try {
            ZoneId.of(recordTimeZone);
        } catch (DateTimeException e) { // invalid/unknown zone
            throw new IllegalArgumentException("invalid recordTimeZone: " + recordTimeZone, e);
        }
    }
}
