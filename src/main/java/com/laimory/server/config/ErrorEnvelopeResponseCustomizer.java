package com.laimory.server.config;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 앱-facing 에러 응답(4xx/5xx)의 스키마를 공통 {@link com.laimory.server.common.ApiResponse} envelope로 통일한다.
 *
 * <p>컨트롤러 인터페이스가 에러 {@code @ApiResponse}마다 {@code content = @Content(@Schema(implementation = ApiResponse.class))}를
 * 반복 선언하던 것을 이 한 곳으로 모은다. 두 가지를 교정한다:
 * <ul>
 *   <li>content가 비어 있는 에러 응답(예: {@code Void} 반환 콜백) → envelope 부착.</li>
 *   <li>springdoc이 에러 응답을 <b>반환 타입</b>(성공 envelope 변형 {@code ApiResponseXxx})으로 자동 채운 경우
 *       → {@code body=null}인 제네릭 envelope로 교정.</li>
 * </ul>
 *
 * <p>{@code SystemController}처럼 평문(비-envelope) 스키마를 명시한 응답은 {@code $ref}가 envelope 계열이 아니므로
 * 손대지 않아, envelope 미적용 엔드포인트가 자동으로 보존된다.
 */
@Component
public class ErrorEnvelopeResponseCustomizer implements OpenApiCustomizer {

    private static final String ENVELOPE_SCHEMA = "ApiResponse";
    private static final String ENVELOPE_REF = "#/components/schemas/" + ENVELOPE_SCHEMA;
    private static final String JSON = "application/json";

    @Override
    public void customise(OpenAPI openApi) {
        registerEnvelopeSchema(openApi);
        Content envelopeContent = new Content().addMediaType(JSON,
                new MediaType().schema(new Schema<>().$ref(ENVELOPE_REF)));

        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> {
                    ApiResponses responses = operation.getResponses();
                    if (responses == null) {
                        return;
                    }
                    responses.forEach((code, response) -> {
                        if (isErrorCode(code) && needsEnvelope(response)) {
                            response.setContent(envelopeContent);
                        }
                    });
                }));
    }

    /** content가 비었거나(예: Void 반환) envelope 계열 typed 스키마로 자동 채워진 에러 응답만 교정 대상이다. */
    private boolean needsEnvelope(ApiResponse response) {
        Content content = response.getContent();
        if (content == null || content.isEmpty()) {
            return true;
        }
        return content.values().stream()
                .map(MediaType::getSchema)
                .anyMatch(schema -> schema != null && schema.get$ref() != null
                        && schema.get$ref().startsWith(ENVELOPE_REF));
    }

    /** envelope 스키마를 components에 한 번만 등록한다(에러 응답이 $ref로 참조). */
    private void registerEnvelopeSchema(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Components components = openApi.getComponents();
        if (components.getSchemas() != null && components.getSchemas().containsKey(ENVELOPE_SCHEMA)) {
            return;
        }
        Map<String, Schema> schemas = ModelConverters.getInstance().read(com.laimory.server.common.ApiResponse.class);
        schemas.forEach(components::addSchemas);
    }

    /** 3자리 숫자 status code 중 4xx/5xx만 에러로 본다("default" 등 비-숫자 키 제외). */
    private boolean isErrorCode(String code) {
        if (code == null || code.length() != 3) {
            return false;
        }
        char statusClass = code.charAt(0);
        return statusClass == '4' || statusClass == '5';
    }
}
