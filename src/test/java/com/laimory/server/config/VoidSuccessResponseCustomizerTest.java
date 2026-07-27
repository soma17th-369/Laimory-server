package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

class VoidSuccessResponseCustomizerTest {

    private static final String VOID_REF = "#/components/schemas/ApiResponseVoid";

    private final VoidSuccessResponseCustomizer customizer = new VoidSuccessResponseCustomizer();

    @Test
    void voidSuccessComponentAndExampleSerializeAsExplicitJsonNull() throws Exception {
        MediaType voidSuccess = new MediaType().schema(new Schema<>().$ref(VOID_REF));
        OpenAPI openApi = openApiWith(new ApiResponses().addApiResponse("200",
                responseWith("*/*", voidSuccess)));

        customizer.customise(openApi);

        Schema<?> envelope = openApi.getComponents().getSchemas().get("ApiResponseVoid");
        assertThat(envelope.getProperties().get("body").getTypes()).containsExactly("null");
        assertThat(voidSuccess.getExample()).isInstanceOfSatisfying(JsonNode.class, example -> {
            assertThat(example.has("body")).isTrue();
            assertThat(example.get("body").isNull()).isTrue();
        });
        assertThat(firstResponses(openApi).get("200").getContent()).containsOnlyKeys("*/*");

        JsonNode serialized = Json31.mapper().readTree(Json31.mapper().writeValueAsString(openApi));
        assertThat(serialized.at("/components/schemas/ApiResponseVoid/properties/body/type").asText())
                .isEqualTo("null");
        JsonNode componentExample = serialized.at("/components/schemas/ApiResponseVoid/example");
        assertThat(componentExample.has("body")).isTrue();
        assertThat(componentExample.get("body").isNull()).isTrue();
        assertThat(componentExample.at("/header/code").isIntegralNumber()).isTrue();
        assertThat(componentExample.at("/header/code").asInt()).isZero();
        assertThat(componentExample.at("/header/message").asText()).isEmpty();
        JsonNode responseExample = serialized.at("/paths/~1void/get/responses/200/content/*~1*/example");
        assertThat(responseExample.has("body")).isTrue();
        assertThat(responseExample.get("body").isNull()).isTrue();
    }

    @Test
    void onlyNumeric2xxWithExactVoidReferenceIsCustomized() {
        MediaType voidSuccess = new MediaType().schema(new Schema<>().$ref(VOID_REF));
        MediaType typedSuccess = new MediaType()
                .schema(new Schema<>().$ref("#/components/schemas/ApiResponseTokenResponse"));
        MediaType voidError = new MediaType().schema(new Schema<>().$ref(VOID_REF));
        MediaType plainSuccess = new MediaType().schema(new Schema<>().type("string"));
        ApiResponses responses = new ApiResponses()
                .addApiResponse("200", responseWith("*/*", voidSuccess))
                .addApiResponse("201", responseWith("application/json", typedSuccess))
                .addApiResponse("204", responseWith("application/json", plainSuccess))
                .addApiResponse("400", responseWith("application/json", voidError))
                .addApiResponse("default", responseWith("application/json",
                        new MediaType().schema(new Schema<>().$ref(VOID_REF))));
        OpenAPI openApi = openApiWith(responses);

        customizer.customise(openApi);

        assertThat(voidSuccess.getExample()).isNotNull();
        assertThat(typedSuccess.getExample()).isNull();
        assertThat(plainSuccess.getExample()).isNull();
        assertThat(voidError.getExample()).isNull();
        assertThat(responses.get("default").getContent().get("application/json").getExample()).isNull();
    }

    @Test
    void missingGeneratedElementsAreSafeNoOps() {
        assertThatCode(() -> customizer.customise(new OpenAPI())).doesNotThrowAnyException();
        assertThatCode(() -> customizer.customise(new OpenAPI().components(new Components()
                .addSchemas("ApiResponseVoid", new Schema<>().type("object")))))
                .doesNotThrowAnyException();
    }

    private OpenAPI openApiWith(ApiResponses responses) {
        Schema<Object> envelope = new Schema<>()
                .type("object")
                .addProperty("header", new Schema<>().type("object"))
                .addProperty("body", new Schema<>());
        return new OpenAPI()
                .components(new Components().addSchemas("ApiResponseVoid", envelope))
                .path("/void", new PathItem().get(new Operation().responses(responses)));
    }

    private ApiResponse responseWith(String contentType, MediaType mediaType) {
        return new ApiResponse().description("response")
                .content(new Content().addMediaType(contentType, mediaType));
    }

    private ApiResponses firstResponses(OpenAPI openApi) {
        return openApi.getPaths().get("/void").getGet().getResponses();
    }
}
