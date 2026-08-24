package com.laimory.server.timeline.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.timeline.controller.TimelineRecordApi;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;

/** 수동 Event 생성 request와 응답 envelope의 OpenAPI 계약을 인프라 없이 고정한다. */
class CreateTimelineEventRequestSchemaTest {

    @Test
    void eventCreate_requiresExactlyTheFiveKeys_andOnlyMemoIsOptional() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(CreateTimelineEventRequest.class);
        Schema request = schemas.get("CreateTimelineEventRequest");

        assertThat(request.getRequired())
                .containsExactlyInAnyOrder("eventType", "title", "subtitle", "startAt", "endAt");
        assertThat(request.getProperties().keySet())
                .containsExactlyInAnyOrder("eventType", "title", "subtitle", "startAt", "endAt", "memo");
        // Item/PHOTO 동시 생성과 AI 결과 전용 필드는 request schema에 없다.
        assertThat(request.getProperties())
                .doesNotContainKeys("photosToAdd", "question", "place", "address", "items");
    }

    @Test
    void eventCreate_returnsTimelineEventResponseEnvelope() {
        Method operation = java.util.Arrays.stream(TimelineRecordApi.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("createTimelineEvent"))
                .findFirst()
                .orElseThrow();

        ResolvableType responseEntity = ResolvableType.forMethodReturnType(operation);
        ResolvableType apiResponse = responseEntity.getGeneric(0);
        assertThat(apiResponse.resolve()).isEqualTo(ApiResponse.class);
        assertThat(apiResponse.getGeneric(0).resolve()).isEqualTo(TimelineEventResponse.class);
    }
}
