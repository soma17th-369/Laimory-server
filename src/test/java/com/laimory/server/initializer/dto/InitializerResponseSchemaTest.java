package com.laimory.server.initializer.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 앱 초기화 응답 DTO의 OpenAPI 계약 고정 — field가 always-present boolean이라 required 목록도 전체여야
 * 한다. 일부만 선언하면 생성된 클라이언트 모델에서 나머지가 nullable로 잘못 나온다. 초기 상태 field가
 * 늘어날 때 required 선언을 빠뜨리면 여기서 깨진다.
 */
class InitializerResponseSchemaTest {

    @Test
    void onboardingCompleted_isRequiredBoolean() {
        Schema<?> schema = resolve();

        Schema<?> onboardingCompleted = schema.getProperties().get("onboardingCompleted");
        assertThat(onboardingCompleted).isNotNull();
        assertThat(onboardingCompleted.getType()).isEqualTo("boolean");
        assertThat(schema.getRequired()).contains("onboardingCompleted");
    }

    @Test
    void everyProperty_isDeclaredRequired() {
        Schema<?> schema = resolve();

        assertThat(schema.getRequired())
                .containsExactlyInAnyOrderElementsOf(schema.getProperties().keySet());
    }

    private static Schema<?> resolve() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(InitializerResponse.class);
        return schemas.get(InitializerResponse.class.getSimpleName());
    }
}
