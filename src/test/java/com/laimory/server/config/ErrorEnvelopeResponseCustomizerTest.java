package com.laimory.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ErrorEnvelopeResponseCustomizer} 단위 검증 — content 없는 에러 응답에만 envelope 스키마를 붙이는지 확인.
 */
class ErrorEnvelopeResponseCustomizerTest {

    private final ErrorEnvelopeResponseCustomizer customizer = new ErrorEnvelopeResponseCustomizer();

    @Test
    void content_없는_에러응답에_envelope_스키마를_부착한다() {
        OpenAPI openApi = openApiWith(new ApiResponses()
                .addApiResponse("400", new ApiResponse().description("bad request")));

        customizer.customise(openApi);

        ApiResponse error = firstResponses(openApi).get("400");
        assertThat(error.getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ApiResponse");
        // 참조 대상인 envelope 스키마가 components에 등록됐다
        assertThat(openApi.getComponents().getSchemas()).containsKey("ApiResponse");
    }

    @Test
    void 성공응답은_건드리지_않는다() {
        MediaType success = new MediaType().schema(new Schema<>().type("object"));
        OpenAPI openApi = openApiWith(new ApiResponses().addApiResponse("200",
                new ApiResponse().description("ok").content(new Content().addMediaType("application/json", success))));

        customizer.customise(openApi);

        assertThat(firstResponses(openApi).get("200").getContent().get("application/json")).isSameAs(success);
    }

    @Test
    void 이미_content가_있는_에러응답은_보존한다() {
        // SystemController처럼 useReturnTypeSchema로 평문 JSON을 명시한 5xx가 덮이지 않는지
        MediaType plain = new MediaType().schema(new Schema<>().type("object"));
        OpenAPI openApi = openApiWith(new ApiResponses().addApiResponse("503",
                new ApiResponse().description("down").content(new Content().addMediaType("application/json", plain))));

        customizer.customise(openApi);

        assertThat(firstResponses(openApi).get("503").getContent().get("application/json")).isSameAs(plain);
    }

    @Test
    void 성공타입으로_자동채워진_에러응답을_제네릭_envelope로_교정한다() {
        // springdoc이 ApiResponse<X> 반환 타입으로 에러 응답을 자동 채운 상황(ApiResponseXxx) → 제네릭 ApiResponse로 교정
        MediaType typed = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponseTokenResponse"));
        OpenAPI openApi = openApiWith(new ApiResponses().addApiResponse("401", new ApiResponse().description("unauthorized")
                .content(new Content().addMediaType("application/json", typed))));

        customizer.customise(openApi);

        assertThat(firstResponses(openApi).get("401").getContent().get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ApiResponse");
    }

    @Test
    void 숫자가_아닌_상태코드는_무시한다() {
        OpenAPI openApi = openApiWith(new ApiResponses()
                .addApiResponse("default", new ApiResponse().description("fallback")));

        customizer.customise(openApi);

        assertThat(firstResponses(openApi).get("default").getContent()).isNull();
    }

    private OpenAPI openApiWith(ApiResponses responses) {
        Operation operation = new Operation().responses(responses);
        return new OpenAPI().path("/x", new PathItem().get(operation));
    }

    private ApiResponses firstResponses(OpenAPI openApi) {
        return openApi.getPaths().get("/x").getGet().getResponses();
    }
}
