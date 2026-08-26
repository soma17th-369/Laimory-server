package com.laimory.server.common;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * record 관련 요청 값의 공용 검증. record_date는 서버가 계산하지 않는다 — 클라이언트가 요청에
 * 명시한 선택 날짜가 단일 권위다(과거 정오 경계 파생은 #164에서 삭제).
 *
 * <p>{@code recordTimeZone}은 recordAt(실제 작성 벽시계 시각)의 저장(역산: 저장된 벽시계의 절대시각 해석)용으로만
 * 보존되므로, 유효성만 {@link #requireValidTimeZone(String)}로 검증한다(잘못된 zone → {@link IllegalArgumentException} → 400).
 *
 * <p>{@code recordDate}는 ISO parse만으로는 MySQL {@code DATE}가 담지 못하는 값(연도 4자리 밖)이 통과하므로
 * 입력 경계에서 {@link #requireValidRecordDate(LocalDate)}로 범위를 좁힌다. 하루 기록을 실제로 만드는
 * 경로는 {@link #requireNotFutureRecordDate(LocalDate, String, Instant)}로 미래 날짜도 거절한다 —
 * 오늘 판정 기준은 서버 zone이 아니라 그 기록의 {@code recordTimeZone}이다.
 */
public final class RecordDates {

    /** MySQL 8.0 {@code DATE}의 하한(양끝 포함). */
    public static final LocalDate MIN_RECORD_DATE = LocalDate.of(1000, 1, 1);

    /** MySQL 8.0 {@code DATE}의 상한(양끝 포함). */
    public static final LocalDate MAX_RECORD_DATE = LocalDate.of(9999, 12, 31);

    private RecordDates() {
    }

    /**
     * 저장·조회 경계로 넘기기 전에 MySQL {@code DATE} 범위 안인지 검증한다. ISO parse는 성공하지만
     * DB가 담을 수 없는 값(예: {@code 0999-12-31}, {@code +10000-01-01})을 service 호출 전에 400으로 끊는다.
     */
    public static void requireValidRecordDate(LocalDate recordDate) {
        if (recordDate == null) {
            throw new IllegalArgumentException("recordDate must not be null");
        }
        if (recordDate.isBefore(MIN_RECORD_DATE) || recordDate.isAfter(MAX_RECORD_DATE)) {
            throw new IllegalArgumentException("recordDate must be between "
                    + MIN_RECORD_DATE + " and " + MAX_RECORD_DATE);
        }
    }

    /**
     * 범위 검증에 더해 요청 timezone 기준 오늘보다 미래인지 검증한다. 아직 오지 않은 날의 하루 기록을
     * 만들거나 확정하는 것을 부수효과 전에 거절한다.
     *
     * <p>오늘은 서버 zone이 아니라 {@code recordTimeZone}에서 해석한다 — 같은 순간이라도 사용자가 있는
     * 지역에 따라 날짜가 다르므로, 서버 기준으로 판정하면 정상 요청이 거절되거나 미래 기록이 통과한다.
     */
    public static void requireNotFutureRecordDate(LocalDate recordDate, String recordTimeZone, Instant now) {
        requireValidRecordDate(recordDate);
        requireValidTimeZone(recordTimeZone);
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        LocalDate today = now.atZone(ZoneId.of(recordTimeZone)).toLocalDate();
        if (recordDate.isAfter(today)) {
            throw new IllegalArgumentException("recordDate must not be in the future");
        }
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
