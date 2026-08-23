package com.laimory.server.config;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * 앱-facing {@code ApiResponse<Void>} 성공 응답을 OpenAPI 3.1의 명시적 JSON null 계약으로 교정한다.
 *
 * <p>springdoc은 generic {@code Void} body를 빈 schema로 만들 수 있어 Swagger UI가 문자열이나 빈 객체
 * placeholder를 표시한다. 실제 runtime envelope는 {@code body:null}이므로 component와 성공 example을
 * 같은 값으로 맞춘다.
 */
@Component
public class VoidSuccessResponseCustomizer implements OpenApiCustomizer {

    private static final String VOID_SCHEMA = "ApiResponseVoid";
    private static final String VOID_REF = "#/components/schemas/" + VOID_SCHEMA;

    @Override
    public void customise(OpenAPI openApi) {
        Schema<?> voidEnvelope = findVoidEnvelope(openApi);
        if (voidEnvelope == null || voidEnvelope.getProperties() == null
                || !voidEnvelope.getProperties().containsKey("body")) {
            return;
        }

        voidEnvelope.getProperties().put("body", new JsonSchema().typesItem("null"));
        voidEnvelope.setExample(successExample());

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
                        if (!isSuccessCode(code) || response.getContent() == null) {
                            return;
                        }
                        response.getContent().values().stream()
                                .filter(this::referencesVoidEnvelope)
                                .forEach(mediaType -> mediaType.setExample(successExample()));
                    });
                }));
    }

    private Schema<?> findVoidEnvelope(OpenAPI openApi) {
        if (openApi == null || openApi.getComponents() == null) {
            return null;
        }
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        return schemas == null ? null : schemas.get(VOID_SCHEMA);
    }

    private boolean referencesVoidEnvelope(MediaType mediaType) {
        return mediaType != null && mediaType.getSchema() != null
                && VOID_REF.equals(mediaType.getSchema().get$ref());
    }

    private boolean isSuccessCode(String code) {
        return code != null && code.length() == 3 && code.charAt(0) == '2'
                && code.chars().allMatch(Character::isDigit);
    }

    private ObjectNode successExample() {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode header = nodes.objectNode()
                .put("code", 0)
                .put("message", "");
        ObjectNode example = nodes.objectNode();
        example.set("header", header);
        example.set("body", nodes.nullNode());
        return example;
    }
}
