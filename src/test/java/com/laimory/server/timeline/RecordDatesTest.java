package com.laimory.server.timeline;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.common.RecordDates;
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
}
