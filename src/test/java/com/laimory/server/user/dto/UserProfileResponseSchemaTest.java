package com.laimory.server.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 내 회원 정보 응답 OpenAPI 계약 고정. {@code useReturnTypeSchema=true}라 DTO annotation이 field-level
 * 문서 권위다 — 런타임의 "userId 항상 존재(non-null)"와 "nickname key 항상 존재 + 값 없으면 명시적
 * JSON null" 계약이 스키마에도 required(+ nickname만 nullable)로 드러나는지 해석 결과로 검증한다.
 */
class UserProfileResponseSchemaTest {

    @Test
    void userId_exposedAsRequiredNonNullableInteger() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(UserProfileResponse.class);
        Schema<?> response = schemas.get("UserProfileResponse");
        assertThat(response).isNotNull();

        Schema<?> userId = (Schema<?>) response.getProperties().get("userId");
        assertThat(userId).isNotNull();
        assertThat(userId.getType()).isEqualTo("integer");
        // 회원 행 PK라 값이 항상 있다 — nullable로 문서화하지 않는다.
        assertThat(userId.getNullable()).isNull();
        assertThat(userId.getDescription()).isNotBlank();
        assertThat(response.getRequired()).contains("userId");
    }

    @Test
    void nickname_exposedAsRequiredNullableString() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(UserProfileResponse.class);
        Schema<?> response = schemas.get("UserProfileResponse");
        assertThat(response).isNotNull();

        Schema<?> nickname = (Schema<?>) response.getProperties().get("nickname");
        assertThat(nickname).isNotNull();
        assertThat(nickname.getType()).isEqualTo("string");
        // 값 없음은 key 생략이 아니라 명시적 JSON null — nullable로 문서화된다.
        assertThat(nickname.getNullable()).isTrue();
        assertThat(nickname.getDescription()).isNotBlank();
        // key 자체는 항상 존재하는 공개 계약 — required로 문서화된다.
        assertThat(response.getRequired()).contains("nickname");
    }
}
