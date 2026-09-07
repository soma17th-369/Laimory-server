package com.laimory.server.terms.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.terms.entity.TermDocumentId;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 두 약관 응답 DTO의 OpenAPI 계약 고정 — Android가 읽는 문서에서 {@code content}가 사라지고
 * {@code contentUrl}이 required URI 문자열로 남는지 검증한다. 원문 field가 실수로 되살아나면 여기서
 * 깨진다. 두 DTO의 field는 전부 always-present이므로 required 목록도 전체여야 한다 — 일부만 선언하면
 * 생성된 클라이언트 모델에서 나머지가 nullable로 잘못 나온다.
 */
class TermResponseSchemaTest {

    @ParameterizedTest
    @ValueSource(classes = {TermResponse.class, TermAgreementResponse.class})
    void contentUrl_isRequiredUriString_andContentIsGone(Class<?> dtoType) {
        Schema<?> schema = resolve(dtoType);

        assertThat(schema.getProperties()).doesNotContainKeys("content", "required", "effectiveAt");
        Schema<?> contentUrl = schema.getProperties().get("contentUrl");
        assertThat(contentUrl).isNotNull();
        assertThat(contentUrl.getType()).isEqualTo("string");
        assertThat(contentUrl.getFormat()).isEqualTo("uri");
        assertThat(schema.getRequired()).contains("contentUrl");
    }

    @ParameterizedTest
    @ValueSource(classes = {TermResponse.class, TermAgreementResponse.class})
    void version_isRequiredCanonicalString(Class<?> dtoType) {
        Schema<?> schema = resolve(dtoType);

        Schema<?> version = schema.getProperties().get("version");
        assertThat(version.getType()).isEqualTo("string");
        assertThat(version.getPattern()).isEqualTo(TermDocumentId.VERSION_PATTERN_TEXT);
        assertThat(version.getMaxLength()).isEqualTo(TermDocumentId.VERSION_MAX_LENGTH);
        assertThat(schema.getRequired()).contains("version");
    }

    @ParameterizedTest
    @ValueSource(classes = {TermResponse.class, TermAgreementResponse.class})
    void everyProperty_isDeclaredRequired(Class<?> dtoType) {
        Schema<?> schema = resolve(dtoType);

        assertThat(schema.getRequired())
                .containsExactlyInAnyOrderElementsOf(schema.getProperties().keySet());
    }

    private static Schema<?> resolve(Class<?> dtoType) {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(dtoType);
        return schemas.get(dtoType.getSimpleName());
    }
}
