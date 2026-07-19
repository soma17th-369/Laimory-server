package com.laimory.server.timeline.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 폴링 응답 OpenAPI 계약 고정. {@code useReturnTypeSchema=true}라 DTO annotation이 field-level 문서 권위다 —
 * {@code elapsedSeconds}가 optional integer(int64)·minimum 0으로 노출되는지 스키마 해석 결과로 검증한다.
 */
class DraftTaskStatusResponseSchemaTest {

    @Test
    void elapsedSeconds_exposedAsOptionalInt64WithMinimumZero() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(DraftTaskStatusResponse.class);
        Schema<?> response = schemas.get("DraftTaskStatusResponse");
        assertThat(response).isNotNull();

        Schema<?> elapsed = (Schema<?>) response.getProperties().get("elapsedSeconds");
        assertThat(elapsed).isNotNull();
        assertThat(elapsed.getType()).isEqualTo("integer");
        assertThat(elapsed.getFormat()).isEqualTo("int64");
        assertThat(elapsed.getMinimum()).isEqualByComparingTo(BigDecimal.ZERO);
        // PROCESSING 전용·legacy 생략 의미가 설명에 실린다(정확 문구는 계약이 아님 — 존재만 고정).
        assertThat(elapsed.getDescription()).isNotBlank();
        // optional: PROCESSING에서만 실리고 terminal/legacy는 생략되므로 required로 선언되지 않는다.
        assertThat(response.getRequired() == null || !response.getRequired().contains("elapsedSeconds")).isTrue();
    }
}
