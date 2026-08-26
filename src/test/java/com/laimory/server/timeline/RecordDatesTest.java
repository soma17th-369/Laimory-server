package com.laimory.server.timeline;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.common.RecordDates;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * timezone 유효성 검증 단위. 인프라 0.
 * record_date는 서버가 계산하지 않는다 — 클라 명시 수신(#164)으로 정오 경계 파생과 그 테스트는 삭제됐다.
 */
class RecordDatesTest {

    @Test
    void requireValidTimeZone_validZone_doesNotThrow() {
        assertThatCode(() -> RecordDates.requireValidTimeZone("Asia/Seoul")).doesNotThrowAnyException();
        assertThatCode(() -> RecordDates.requireValidTimeZone("UTC")).doesNotThrowAnyException();
    }

    @Test
    void requireValidTimeZone_invalidZone_throws() {
        assertThatThrownBy(() -> RecordDates.requireValidTimeZone("Not/AZone"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireValidTimeZone_null_throws() {
        assertThatThrownBy(() -> RecordDates.requireValidTimeZone(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireValidRecordDate_mysqlDateBounds_areInclusive() {
        assertThatCode(() -> RecordDates.requireValidRecordDate(LocalDate.of(1000, 1, 1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> RecordDates.requireValidRecordDate(LocalDate.of(9999, 12, 31)))
                .doesNotThrowAnyException();
    }

    @Test
    void requireValidRecordDate_outsideMysqlDateRange_throws() {
        // ISO parse는 통과하지만 MySQL DATE가 담지 못하는 값이다.
        assertThatThrownBy(() -> RecordDates.requireValidRecordDate(LocalDate.of(999, 12, 31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordDates.requireValidRecordDate(LocalDate.of(10000, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordDates.requireValidRecordDate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireNotFutureRecordDate_todayAndPast_areAllowed() {
        Instant now = Instant.parse("2026-08-05T14:00:00Z"); // 2026-08-05 23:00 KST
        assertThatCode(() -> RecordDates.requireNotFutureRecordDate(
                LocalDate.of(2026, 8, 5), "Asia/Seoul", now)).doesNotThrowAnyException();
        assertThatCode(() -> RecordDates.requireNotFutureRecordDate(
                LocalDate.of(2020, 1, 1), "Asia/Seoul", now)).doesNotThrowAnyException();
    }

    @Test
    void requireNotFutureRecordDate_future_throws() {
        Instant now = Instant.parse("2026-08-05T14:00:00Z");
        assertThatThrownBy(() -> RecordDates.requireNotFutureRecordDate(
                LocalDate.of(2026, 8, 6), "Asia/Seoul", now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireNotFutureRecordDate_todayIsResolvedInRequestZone_notServerZone() {
        // 같은 instant가 UTC+14에서는 이미 08-06, UTC-11에서는 아직 08-05다.
        Instant now = Instant.parse("2026-08-05T14:00:00Z");
        LocalDate date = LocalDate.of(2026, 8, 6);

        assertThatCode(() -> RecordDates.requireNotFutureRecordDate(date, "Pacific/Kiritimati", now))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RecordDates.requireNotFutureRecordDate(date, "Pacific/Midway", now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireNotFutureRecordDate_alsoEnforcesRangeAndZoneValidity() {
        Instant now = Instant.parse("2026-08-05T14:00:00Z");
        assertThatThrownBy(() -> RecordDates.requireNotFutureRecordDate(
                LocalDate.of(999, 12, 31), "Asia/Seoul", now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecordDates.requireNotFutureRecordDate(
                LocalDate.of(2026, 8, 5), "Not/AZone", now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
