package com.laimory.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
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
    void masksFirebaseInstallationIdAliasesWithoutRawValue() throws Exception {
        // FID는 민감 opaque 식별자 — push 등록 body(중첩·표기 변형 포함)에서 원문이 남으면 안 된다.
        String rawFid = "RAW_FID_174_NEVER_LOG";
        String body = """
                {"firebaseInstallationId":"%s","nested":{"firebase_installation_id":"%s"},
                 "list":[{"firebase-installation-id":"%s"}],"safe":"kept"}
                """.formatted(rawFid, rawFid, rawFid);

        String masked = maskRequest("/a/api/v1/push-registrations", body);
        JsonNode json = objectMapper.readTree(masked);

        assertThat(json.get("firebaseInstallationId").asText()).isEqualTo("***");
        assertThat(json.at("/nested/firebase_installation_id").asText()).isEqualTo("***");
        assertThat(json.at("/list/0/firebase-installation-id").asText()).isEqualTo("***");
        assertThat(json.get("safe").asText()).isEqualTo("kept");
        assertThat(masked).doesNotContain(rawFid);
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

    @ParameterizedTest
    @MethodSource("privacyRequestPaths")
    void privacyRequestBodiesAreFullyMaskedBeforeJsonParsing(String method, String path) {
        ObjectMapper unusedMapper = mock(ObjectMapper.class);
        AccessLogBodyMasker privacyMasker = new AccessLogBodyMasker(unusedMapper);
        String raw = "{\"memo\":\"RAW_PRIVACY_281_NEVER_LOG\"}";
        MockHttpServletRequest request = jsonRequest(method, path, raw);

        String masked = privacyMasker.maskRequest(request, bytes(raw), false);

        assertThat(masked)
                .isEqualTo(AccessLogBodyMasker.MASKED_PRIVACY_BODY)
                .doesNotContain("RAW_PRIVACY_281_NEVER_LOG");
        verifyNoInteractions(unusedMapper);
    }

    private static Stream<Arguments> privacyRequestPaths() {
        return Stream.of(
                Arguments.of("POST", "/a/api/v1/timeline/drafts"),
                Arguments.of("PATCH", "/a/api/v12/timeline/events/42"),
                Arguments.of("PUT", "/a/api/v1/timeline/events/42/memo"),
                Arguments.of("POST", "/s/api/v1/timeline/drafts/task-281/result"),
                Arguments.of("POST", "/s/api/v1/timeline/drafts/task-281/callback"),
                Arguments.of("POST", "/s/api/v2/user-memory/updates/task-281/result"));
    }

    @ParameterizedTest
    @MethodSource("privacyResponsePaths")
    void privacyResponseBodiesAreFullyMaskedBeforeJsonParsing(String path) {
        ObjectMapper unusedMapper = mock(ObjectMapper.class);
        AccessLogBodyMasker privacyMasker = new AccessLogBodyMasker(unusedMapper);
        String raw = "{\"title\":\"RAW_PRIVACY_281_NEVER_LOG\"}";

        String masked = privacyMasker.maskResponse(
                new MockHttpServletRequest("GET", path), jsonResponse(), bytes(raw), false);

        assertThat(masked)
                .isEqualTo(AccessLogBodyMasker.MASKED_PRIVACY_BODY)
                .doesNotContain("RAW_PRIVACY_281_NEVER_LOG");
        verifyNoInteractions(unusedMapper);
    }

    private static Stream<String> privacyResponsePaths() {
        return Stream.of(
                "/a/api/v1/timeline/drafts/task-281",          // draft polling(전체 상태 — SUCCESS만이 아님)
                "/a/api/v1/timeline/daily-records",
                "/a/api/v3/timeline/daily-records/2026-08-11",
                "/a/api/v1/timeline/daily-records/by-id/42",
                "/a/api/v1/timeline/events/42",
                "/api/v1/terms",                               // 약관 원문 전체(공개 조회)
                "/a/api/v1/terms/agreements");                 // 약관 원문 전체(동의 이력)
    }

    @Test
    void privacyPathJudgmentPrecedesBodyChecks() {
        // malformed JSON도 [unavailable...]이 아닌 같은 placeholder
        assertThat(maskRequest("POST", "/a/api/v1/timeline/drafts", "{RAW_PRIVACY_281_NEVER_LOG"))
                .isEqualTo(AccessLogBodyMasker.MASKED_PRIVACY_BODY);

        // 비JSON content type도 null이 아닌 placeholder
        MockHttpServletRequest nonJson = new MockHttpServletRequest("PUT", "/a/api/v1/timeline/events/1/memo");
        nonJson.setContentType("text/plain");
        assertThat(masker.maskRequest(nonJson, bytes("raw text memo"), false))
                .isEqualTo(AccessLogBodyMasker.MASKED_PRIVACY_BODY);

        // 캡처 상한 초과(truncate)도 [too large...]가 아닌 placeholder
        assertThat(masker.maskRequest(
                jsonRequest("POST", "/s/api/v1/timeline/drafts/task-281/result", "{}"), bytes("{}"), true))
                .isEqualTo(AccessLogBodyMasker.MASKED_PRIVACY_BODY);
        assertThat(masker.maskResponse(new MockHttpServletRequest("GET", "/a/api/v1/timeline/drafts/task-281"),
                jsonResponse(), bytes("{}"), true))
                .isEqualTo(AccessLogBodyMasker.MASKED_PRIVACY_BODY);

        // 미열람(empty) body도 null이 아닌 placeholder — body 존재 형태 정보도 남기지 않는다
        assertThat(masker.maskRequest(
                jsonRequest("POST", "/a/api/v1/timeline/drafts", "{}"), new byte[0], false))
                .isEqualTo(AccessLogBodyMasker.MASKED_PRIVACY_BODY);
    }

    @Test
    void methodDistinguishesPrivacyTargetsOnSamePath() {
        // 같은 경로라도 method가 다르면 대상이 아니다 — 목록 GET request는 일반 규칙으로 남는다.
        assertThat(maskRequest("GET", "/a/api/v1/timeline/drafts", "{\"safe\":\"kept\"}"))
                .isEqualTo("{\"safe\":\"kept\"}");
        // response도 method 판정 — draft 생성 POST의 response(taskId 등)는 마스킹하지 않는다.
        assertThat(masker.maskResponse(new MockHttpServletRequest("POST", "/a/api/v1/timeline/drafts"),
                jsonResponse(), bytes("{\"taskId\":\"task-281\"}"), false))
                .isEqualTo("{\"taskId\":\"task-281\"}");
    }

    @Test
    void nonPrivacyPathsKeepFieldLevelMasking() {
        // presign(photo-uploads)은 대상 아님 — 기존 필드 기반 secret 마스킹이 그대로 적용된다.
        String presign = maskRequest("POST", "/a/api/v1/timeline/drafts/photo-uploads",
                "{\"filename\":\"a.jpg\",\"uploadUrl\":\"RAW_URL_281_NEVER_LOG\"}");
        assertThat(presign).contains("\"filename\":\"a.jpg\"").doesNotContain("RAW_URL_281_NEVER_LOG");

        // AI input 응답은 저장 시점에 치환된 서버간 응답이라 대상 아님.
        assertThat(masker.maskResponse(
                new MockHttpServletRequest("GET", "/s/api/v1/timeline/drafts/task-281/input"),
                jsonResponse(), bytes("{\"items\":[]}"), false))
                .isEqualTo("{\"items\":[]}");

        // drafts 목록 GET response는 polling 단건과 달리 대상 아님.
        assertThat(masker.maskResponse(new MockHttpServletRequest("GET", "/a/api/v1/timeline/drafts"),
                jsonResponse(), bytes("{\"tasks\":[]}"), false))
                .isEqualTo("{\"tasks\":[]}");
    }

    @Test
    void termAgreementPostRequestKeepsFieldLevelMasking() {
        // 동의 POST request에는 약관 원문이 없고 type/version뿐 — 전체 치환 대상이 아니라 일반 JSON 규칙이다.
        assertThat(maskRequest("POST", "/a/api/v1/terms/agreements",
                "{\"agreements\":[{\"termType\":\"TERMS_OF_SERVICE\",\"version\":\"2026-08-15\"}]}"))
                .contains("\"termType\":\"TERMS_OF_SERVICE\"")
                .contains("\"version\":\"2026-08-15\"");
        // 같은 경로라도 GET response만 전체 치환된다(위 privacyResponsePaths 참여) — POST response는 body=null이라 대상 아님.
        assertThat(masker.maskResponse(new MockHttpServletRequest("POST", "/a/api/v1/terms/agreements"),
                jsonResponse(), bytes("{\"header\":{\"code\":0}}"), false))
                .isEqualTo("{\"header\":{\"code\":0}}");
    }

    @Test
    void authPathJudgmentPrecedesBodyChecks() {
        // 순서 교정 회귀 방지 — 비JSON content type이어도 auth 경로는 placeholder다.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/token");
        request.setContentType("text/plain");

        assertThat(masker.maskRequest(request, bytes("appCode=RAW_SECRET_281_NEVER_LOG"), false))
                .isEqualTo(AccessLogBodyMasker.MASKED_AUTH_BODY);
    }

    @Test
    void malformedJsonUsesPlaceholderWithoutRawInput() {
        String raw = "{RAW_SECRET_152_NEVER_LOG";

        String masked = maskRequest("/api/v1/test", raw);

        assertThat(masked).isEqualTo("[unavailable: invalid or unmaskable JSON]");
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

        assertThat(masker.maskResponse(new MockHttpServletRequest("GET", "/api/v1/test"), response, bytes("{}"), true))
                .isEqualTo("[too large: body exceeds 524288 bytes]");
    }

    @Test
    void responseJsonBytesAreDecodedWithoutServletFallbackCharset() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getContentType()).thenReturn("application/json");
        when(response.getCharacterEncoding()).thenReturn(StandardCharsets.ISO_8859_1.name());

        assertThat(masker.maskResponse(new MockHttpServletRequest("GET", "/api/v1/test"),
                response, bytes("{\"title\":\"한글 일기\"}"), false))
                .isEqualTo("{\"title\":\"한글 일기\"}");
    }

    @Test
    void compactJsonIsSanitizedToBoundedTextPreview() {
        String body = "{\"safe\":\"" + "가".repeat(AccessLogBodyMasker.MAX_LOGGED_CHARS + 1000) + "\"}";

        String masked = maskRequest("/api/v1/test", body);

        assertThat(masked).hasSize(65536).endsWith("…");
    }

    private String maskRequest(String path, String body) {
        return maskRequest("POST", path, body);
    }

    private String maskRequest(String method, String path, String body) {
        MockHttpServletRequest request = jsonRequest(method, path, body);
        return masker.maskRequest(request, bytes(body), false);
    }

    private static MockHttpServletRequest jsonRequest(String path, String body) {
        return jsonRequest("POST", path, body);
    }

    private static MockHttpServletRequest jsonRequest(String method, String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContentType("application/json");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent(bytes(body));
        return request;
    }

    private static MockHttpServletResponse jsonResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("application/json");
        return response;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
