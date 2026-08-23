package com.laimory.server.common;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * record 관련 요청 값의 공용 검증. record_date는 서버가 계산하지 않는다 — 클라이언트가 요청에
 * 명시한 선택 날짜가 단일 권위다(과거 정오 경계 파생은 #164에서 삭제).
 *
 * <p>{@code recordTimeZone}은 recordAt(실제 작성 벽시계 시각)의 저장(역산: 저장된 벽시계의 절대시각 해석)용으로만
 * 보존되므로, 유효성만 {@link #requireValidTimeZone(String)}로 검증한다(잘못된 zone → {@link IllegalArgumentException} → 400).
 */
public final class RecordDates {

    private RecordDates() {
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
