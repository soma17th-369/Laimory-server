package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessLogBodyMaskerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AccessLogBodyMasker masker = new AccessLogBodyMasker(objectMapper);

    @Test
    void recursivelyMasksSecretAliasesAndValuesRegardlessOfType() throws Exception {
        String rawSecret = "RAW_REFRESH_TOKEN_152_NEVER_LOG";
        String body = """
                {"Password":123,"nested":[{"app-code":true,"refresh_token":"%s","safe":"kept"}]}
                """.formatted(rawSecret);

        String masked = maskRequest("/api/v1/test", body);
        JsonNode json = objectMapper.readTree(masked);

        assertThat(json.get("Password").asText()).isEqualTo("***");
        assertThat(json.at("/nested/0/app-code").asText()).isEqualTo("***");
        assertThat(json.at("/nested/0/refresh_token").asText()).isEqualTo("***");
        assertThat(json.at("/nested/0/safe").asText()).isEqualTo("kept");
        assertThat(masked).doesNotContain(rawSecret);
    }

    @Test
    void supportsRootArrayAndScalarJson() {
        assertThat(maskRequest("/api/v1/test", "[1,true,{\"safe\":\"ok\"}]"))
                .isEqualTo("[1,true,{\"safe\":\"ok\"}]");
        assertThat(maskRequest("/api/v1/test", "\"plain root value\""))
                .isEqualTo("\"plain root value\"");
    }

    @Test
    void masksXAmzValueCaseInsensitively() {
        String masked = maskRequest("/api/v1/test",
                "{\"url\":\"https://upload.example/path?x-aMz-Signature=never-log\"}");

        assertThat(masked).isEqualTo("{\"url\":\"***\"}");
    }

    @ParameterizedTest
    @MethodSource("authBodies")
    void authBodiesAreFullyMaskedBeforeJsonParsing(String path, String body) {
        ObjectMapper unusedMapper = mock(ObjectMapper.class);
        AccessLogBodyMasker authMasker = new AccessLogBodyMasker(unusedMapper);
        MockHttpServletRequest request = jsonRequest(path, body);

        assertThat(authMasker.maskRequest(request, bytes(body), false))
                .isEqualTo(AccessLogBodyMasker.MASKED_AUTH_BODY);
        verifyNoInteractions(unusedMapper);
    }

    private static Stream<Arguments> authBodies() {
        return Stream.of(
                Arguments.of("/api/v1/auth/token", "{\"appCode\":\"secret\"}"),
                Arguments.of("/api/v12/auth/refresh", "[\"secret\"]"),
                Arguments.of("/api/v3/auth/logout", "\"secret\""),
                Arguments.of("/api/v1/auth/token", "{broken-json")
        );
    }

    @Test
    void malformedJsonUsesPlaceholderWithoutRawInput() {
        String raw = "{RAW_SECRET_152_NEVER_LOG";

        String masked = maskRequest("/api/v1/test", raw);

        assertThat(masked).isEqualTo("[unavailable: malformed JSON]");
        assertThat(masked).doesNotContain(raw);
    }

    @Test
    void nonJsonAndUnreadBodyAreNull() {
        MockHttpServletRequest nonJson = new MockHttpServletRequest("POST", "/api/v1/test");
        nonJson.setContentType("text/plain");

        assertThat(masker.maskRequest(nonJson, bytes("hello"), false)).isNull();

        MockHttpServletRequest unread = jsonRequest("/api/v1/test", "{\"safe\":true}");
        assertThat(masker.maskRequest(unread, new byte[0], false)).isNull();
    }

    @Test
    void overflowUsesTooLargePlaceholder() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("application/json");

        assertThat(masker.maskResponse(response, bytes("{}"),
                AccessLogBodyMasker.CAPTURE_LIMIT_BYTES + 1L, true))
                .isEqualTo("[too large: body exceeds 65536 bytes]");
    }

    @Test
    void compactJsonIsSanitizedToBoundedTextPreview() {
        String body = "{\"safe\":\"" + "가".repeat(9000) + "\"}";

        String masked = maskRequest("/api/v1/test", body);

        assertThat(masked).hasSize(8192).endsWith("…");
    }

    @Test
    void serializationFailureUsesSafePlaceholder() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        JsonNode parsed = objectMapper.readTree("{\"safe\":true}");
        when(failingMapper.readTree("{\"safe\":true}")).thenReturn(parsed);
        when(failingMapper.writeValueAsString(parsed)).thenThrow(new JsonProcessingException("boom") {
        });
        AccessLogBodyMasker failingMasker = new AccessLogBodyMasker(failingMapper);
        MockHttpServletRequest request = jsonRequest("/api/v1/test", "{\"safe\":true}");

        assertThat(failingMasker.maskRequest(request, bytes("{\"safe\":true}"), false))
                .isEqualTo("[unavailable: body masking failed]");
    }

    private String maskRequest(String path, String body) {
        MockHttpServletRequest request = jsonRequest(path, body);
        return masker.maskRequest(request, bytes(body), false);
    }

    private static MockHttpServletRequest jsonRequest(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent(bytes(body));
        return request;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
