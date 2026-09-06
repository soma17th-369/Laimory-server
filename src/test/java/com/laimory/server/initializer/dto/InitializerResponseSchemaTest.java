package com.laimory.server.initializer.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 앱 초기화 응답 DTO의 OpenAPI 계약 고정 — field가 always-present라 required 목록도 전체여야 한다.
 * 일부만 선언하면 생성된 클라이언트 모델에서 나머지가 nullable로 잘못 나온다. 최상위 스키마만 검사하면
 * 중첩 DTO(#434 약관 그룹·원소)의 누락을 놓치므로 resolve된 모든 스키마를 전수 검사한다.
 */
class InitializerResponseSchemaTest {

    @Test
    void onboardingCompleted_isRequiredBoolean() {
        Schema<?> schema = schema("InitializerResponse");

        Schema<?> onboardingCompleted = schema.getProperties().get("onboardingCompleted");
        assertThat(onboardingCompleted).isNotNull();
        assertThat(onboardingCompleted.getType()).isEqualTo("boolean");
        assertThat(schema.getRequired()).contains("onboardingCompleted");
    }

    @Test
    void terms_isRequiredGroup_withRequiredAgreementList() {
        assertThat(schema("InitializerResponse").getRequired()).contains("terms");

        Schema<?> terms = schema("InitializerTermsResponse");
        Schema<?> agreementRequired = terms.getProperties().get("agreementRequired");
        assertThat(agreementRequired).isNotNull();
        assertThat(agreementRequired.getType()).isEqualTo("array");
        assertThat(terms.getRequired()).contains("agreementRequired");

        Schema<?> element = schema("AgreementRequiredTermResponse");
        assertThat(element.getProperties().keySet()).containsExactlyInAnyOrder("termType", "version");
    }

    @Test
    void everySchema_declaresEveryPropertyRequired() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(InitializerResponse.class);
        assertThat(schemas.keySet()).contains("InitializerResponse", "InitializerTermsResponse",
                "AgreementRequiredTermResponse");
        schemas.forEach((name, schema) -> {
            Map<String, Schema> properties = schema.getProperties();
            if (properties == null) {
                return; // property 없는 스키마(enum 등)는 required 전수 계약의 대상이 아니다.
            }
            assertThat(schema.getRequired())
                    .as("%s의 required 선언", name)
                    .containsExactlyInAnyOrderElementsOf(properties.keySet());
        });
    }

    private static Schema<?> schema(String name) {
        Schema<?> schema = ModelConverters.getInstance().readAll(InitializerResponse.class).get(name);
        assertThat(schema).as("스키마 %s", name).isNotNull();
        return schema;
    }
}
