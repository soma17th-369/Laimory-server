package com.laimory.server.timeline.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 월별 경량 조회 항목 DTO의 OpenAPI 계약 고정 — field가 always-present라 required 목록도 전체여야 한다.
 * 일부만 선언하면 생성된 클라이언트 모델에서 나머지가 nullable로 잘못 나온다. 이후 field가 늘어날 때
 * required 선언을 빠뜨리면 여기서 깨진다.
 */
class MonthlyDailyRecordResponseSchemaTest {

    @Test
    void everyProperty_isDeclaredRequired() {
        Schema<?> schema = resolve();

        assertThat(schema.getRequired())
                .containsExactlyInAnyOrderElementsOf(schema.getProperties().keySet());
    }

    @Test
    void status_exposedAsRequiredDraftSavedEnum() {
        Schema<?> schema = resolve();

        Schema<?> status = (Schema<?>) schema.getProperties().get("status");
        assertThat(status).isNotNull();
        assertThat(status.getType()).isEqualTo("string");
        assertThat(status.getEnum()).extracting(Object::toString)
                .containsExactlyInAnyOrder("DRAFT", "SAVED");
        assertThat(schema.getRequired()).contains("status");
    }

    @Test
    void emotionType_exposedAsRequiredNullable() {
        Schema<?> schema = resolve();

        Schema<?> emotionType = (Schema<?>) schema.getProperties().get("emotionType");
        assertThat(emotionType).isNotNull();
        // 값 없음은 key 생략이 아니라 명시적 JSON null — nullable로 문서화된다.
        assertThat(emotionType.getNullable()).isTrue();
        assertThat(schema.getRequired()).contains("emotionType");
    }

    private static Schema<?> resolve() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(MonthlyDailyRecordResponse.class);
        return schemas.get(MonthlyDailyRecordResponse.class.getSimpleName());
    }
}
