package com.laimory.server.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.common.RecordDates;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * record_date 정오 경계 계산 단위 검증. 인프라 0. Instant는 결정적으로 명시 구성(Instant.now() 금지).
 * 정오 경계: local 시각이 정오(12:00) 이전이면 전날, 정오 이상이면 당일.
 */
class RecordDatesTest {

    @Test
    void preNoon_belongsToPreviousDay() {
        // UTC 2026-05-08 11:59 → 정오 이전 → 전날(2026-05-07)
        Instant anchor = LocalDateTime.of(2026, 5, 8, 11, 59).toInstant(ZoneOffset.UTC);

        assertThat(RecordDates.resolveRecordDate(anchor, "UTC")).isEqualTo(LocalDate.of(2026, 5, 7));
    }

    @Test
    void exactlyNoon_belongsToSameDay() {
        // UTC 2026-05-08 12:00 → 정오 → 당일(2026-05-08)
        Instant anchor = LocalDateTime.of(2026, 5, 8, 12, 0).toInstant(ZoneOffset.UTC);

        assertThat(RecordDates.resolveRecordDate(anchor, "UTC")).isEqualTo(LocalDate.of(2026, 5, 8));
    }

    @Test
    void lateNight_belongsToThatDay() {
        // UTC 2026-05-08 23:30 → 정오 이후 → 당일(2026-05-08)
        Instant anchor = LocalDateTime.of(2026, 5, 8, 23, 30).toInstant(ZoneOffset.UTC);

        assertThat(RecordDates.resolveRecordDate(anchor, "UTC")).isEqualTo(LocalDate.of(2026, 5, 8));
    }

    @Test
    void nonUtcZone_appliesBoundaryInLocalTime() {
        // 같은 instant라도 zone에 따라 local 시각이 달라져 경계 판정이 바뀐다.
        // Instant = 2026-05-08T02:30:00Z → Asia/Seoul(+09:00) local 11:30 → 정오 이전 → 전날(2026-05-07)
        Instant anchor = LocalDateTime.of(2026, 5, 8, 2, 30)
                .toInstant(ZoneOffset.UTC);

        assertThat(RecordDates.resolveRecordDate(anchor, "Asia/Seoul")).isEqualTo(LocalDate.of(2026, 5, 7));

        // Instant = 2026-05-08T03:30:00Z → Asia/Seoul local 12:30 → 정오 이후 → 당일(2026-05-08)
        Instant afterNoon = LocalDateTime.of(2026, 5, 8, 3, 30).toInstant(ZoneOffset.UTC);
        assertThat(RecordDates.resolveRecordDate(afterNoon, "Asia/Seoul")).isEqualTo(LocalDate.of(2026, 5, 8));
    }

    @Test
    void nonUtcZone_matchesZoneIdLocalDate() {
        Instant anchor = Instant.parse("2026-05-08T15:00:00Z");
        // 검증 보조: 직접 zone 변환한 결과와 정오 경계 적용 후가 일관
        LocalDateTime seoulLocal = LocalDateTime.ofInstant(anchor, ZoneId.of("Asia/Seoul")); // 2026-05-09 00:00
        assertThat(seoulLocal.toLocalDate()).isEqualTo(LocalDate.of(2026, 5, 9));
        // local 00:00 → 정오 이전 → 전날(2026-05-08)
        assertThat(RecordDates.resolveRecordDate(anchor, "Asia/Seoul")).isEqualTo(LocalDate.of(2026, 5, 8));
    }

    @Test
    void invalidZone_throwsIllegalArgumentException() {
        Instant anchor = Instant.parse("2026-05-08T12:00:00Z");

        assertThatThrownBy(() -> RecordDates.resolveRecordDate(anchor, "Not/AZone"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullAnchor_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> RecordDates.resolveRecordDate(null, "UTC"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullZone_throwsIllegalArgumentException() {
        Instant anchor = Instant.parse("2026-05-08T12:00:00Z");

        assertThatThrownBy(() -> RecordDates.resolveRecordDate(anchor, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
