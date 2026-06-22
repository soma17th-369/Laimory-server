package com.laimory.server.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.common.RecordDates;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * record_date 정오 경계 계산 + timezone 유효성 검증 단위. 인프라 0.
 * anchor는 클라 zone의 벽시계 LocalDateTime이라 날짜 계산엔 zone이 필요 없다.
 */
class RecordDatesTest {

    @Test
    void resolveRecordDate_beforeNoon_isPreviousDay() {
        assertThat(RecordDates.resolveRecordDate(LocalDateTime.of(2026, 5, 8, 11, 59)))
                .isEqualTo(LocalDate.of(2026, 5, 7));
    }

    @Test
    void resolveRecordDate_atNoon_isSameDay() {
        assertThat(RecordDates.resolveRecordDate(LocalDateTime.of(2026, 5, 8, 12, 0)))
                .isEqualTo(LocalDate.of(2026, 5, 8));
    }

    @Test
    void resolveRecordDate_lateNight_isSameDay() {
        assertThat(RecordDates.resolveRecordDate(LocalDateTime.of(2026, 5, 8, 23, 30)))
                .isEqualTo(LocalDate.of(2026, 5, 8));
    }

    @Test
    void resolveRecordDate_justAfterMidnight_isPreviousDay() {
        // 자정 직후(00:00)는 정오 이전 → 전날.
        assertThat(RecordDates.resolveRecordDate(LocalDateTime.of(2026, 5, 9, 0, 0)))
                .isEqualTo(LocalDate.of(2026, 5, 8));
    }

    @Test
    void resolveRecordDate_nullAnchor_throws() {
        assertThatThrownBy(() -> RecordDates.resolveRecordDate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
}
