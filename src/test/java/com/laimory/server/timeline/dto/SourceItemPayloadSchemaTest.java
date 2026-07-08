package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.payload.PhotoPayload;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payload 다형 노출 검증 — springdoc이 쓰는 swagger-core {@link ModelConverters}로 스키마를 직접 뽑아 확인한다(인프라 불필요).
 */
class SourceItemPayloadSchemaTest {

    @Test
    void 요청_payload는_6종_oneOf_스키마를_참조한다() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(SourceItemDto.class);
        Schema payloadProp = (Schema) schemas.get("SourceItemDto").getProperties().get("payload");
        assertThat(payloadProp.get$ref()).isEqualTo("#/components/schemas/TimelineItemPayload");
        assertThat(schemas.get("TimelineItemPayload").getOneOf()).hasSize(6);
    }

    @Test
    void 응답_payload는_JsonNode가_아니라_6종_oneOf_스키마를_참조한다() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(TimelineItemResponse.class);
        Schema payloadProp = (Schema) schemas.get("TimelineItemResponse").getProperties().get("payload");
        // implementation 오버라이드로 JsonNode 대신 TimelineItemPayload를 가리켜야 한다
        assertThat(payloadProp.get$ref()).isEqualTo("#/components/schemas/TimelineItemPayload");
        assertThat(schemas.get("TimelineItemPayload").getOneOf()).hasSize(6);
    }

    @Test
    void 서버파생_photoUrl은_readOnly_클라입력_필드는_아니다() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(PhotoPayload.class);
        Map<String, Schema> props = schemas.get("PhotoPayload").getProperties();
        assertThat(props.get("photoUrl").getReadOnly()).isTrue();
        assertThat(props.get("filename").getReadOnly()).isNull();
    }
}
