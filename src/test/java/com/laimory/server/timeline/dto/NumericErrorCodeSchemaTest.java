package com.laimory.server.timeline.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

/** polling/callback 공개 error code의 OpenAPI 타입이 JSON integer인지 고정한다. */
class NumericErrorCodeSchemaTest {

    @Test
    void pollingErrorAndCallbackErrorCodeAreInt32() {
        Schema<?> polling = ModelConverters.getInstance()
                .read(DraftTaskStatusResponse.class)
                .get("DraftTaskStatusResponse");
        Schema<?> callback = ModelConverters.getInstance()
                .read(DraftTaskCallbackRequest.class)
                .get("DraftTaskCallbackRequest");

        Schema<?> pollingError = polling.getProperties().get("error");
        assertInteger(pollingError);
        assertThat(pollingError.getExample()).isEqualTo(-1009);
        Schema<?> callbackError = callback.getProperties().get("errorCode");
        assertInteger(callbackError);
        assertThat(callbackError.getExample()).isEqualTo(-1008);
    }

    private void assertInteger(Schema<?> schema) {
        assertThat(schema.getType()).isEqualTo("integer");
        assertThat(schema.getFormat()).isEqualTo("int32");
    }
}
