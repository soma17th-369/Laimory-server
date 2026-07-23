package com.laimory.server.timeline.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.timeline.controller.TimelineRecordApi;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Event PATCH request와 legacy memo API의 OpenAPI 계약을 인프라 없이 고정한다. */
class UpdateTimelineEventRequestSchemaTest {

    @Test
    void eventPatch_requiresOnlyTheLegacyFourKeys_andHidesInternalMemoPresence() {
        Map<String, Schema> schemas = ModelConverters.getInstance().readAll(UpdateTimelineEventRequest.class);
        Schema request = schemas.get("UpdateTimelineEventRequest");

        assertThat(request.getRequired())
                .containsExactlyInAnyOrder("title", "subtitle", "startAt", "endAt");
        assertThat(request.getProperties()).containsKeys("eventType", "memo", "photosToAdd");
        assertThat(request.getProperties()).doesNotContainKey("memoPresent");
    }

    @Test
    void manualPhotoPayload_exposesOnlyClientWritableFourFields() {
        Map<String, Schema> schemas = ModelConverters.getInstance()
                .readAll(UpdateTimelineEventPhotoPayloadRequest.class);
        Schema payload = schemas.get("UpdateTimelineEventPhotoPayloadRequest");

        assertThat(payload.getProperties().keySet())
                .containsExactlyInAnyOrder("filename", "clientPhotoUri", "latitude", "longitude");
        assertThat(payload.getProperties()).doesNotContainKeys("description", "photoUrl");
    }

    @Test
    void legacyMemoPut_isDeprecatedInOpenApi() {
        Method memoOperation = java.util.Arrays.stream(TimelineRecordApi.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("updateTimelineEventMemo"))
                .findFirst()
                .orElseThrow();

        Operation operation = memoOperation.getAnnotation(Operation.class);
        assertThat(operation).isNotNull();
        assertThat(operation.deprecated()).isTrue();
    }
}
