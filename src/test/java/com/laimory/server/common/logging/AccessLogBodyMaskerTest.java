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
    void privacyRequestBodiesKeepOnlyAllowlistedSkeleton(String method, String path) {
        // 전 request 경로 공통 규칙(#312) — allowlist 밖 필드는 타입 무관 MASK, 목록 필드(status)만 남는다.
        String raw = "{\"memo\":\"RAW_PRIVACY_281_NEVER_LOG\",\"status\":\"FAILED\"}";
        MockHttpServletRequest request = jsonRequest(method, path, raw);

        assertThat(masker.maskRequest(request, bytes(raw), false))
                .isEqualTo("{\"memo\":\"***\",\"status\":\"FAILED\"}")
                .doesNotContain("RAW_PRIVACY_281_NEVER_LOG");
    }

    private static Stream<Arguments> privacyRequestPaths() {
        return Stream.of(
                Arguments.of("POST", "/a/api/v1/timeline/drafts"),
                Arguments.of("PATCH", "/a/api/v12/timeline/events/42"),
                Arguments.of("PUT", "/a/api/v1/timeline/events/42/memo"),
                Arguments.of("POST", "/a/api/v1/timeline/daily-records/2026-07-08/events"),
                Arguments.of("POST", "/s/api/v1/timeline/drafts/task-281/result"),
                Arguments.of("POST", "/s/api/v1/timeline/drafts/task-281/callback"),
                Arguments.of("POST", "/s/api/v2/user-memory/updates/task-281/result"));
    }

    @ParameterizedTest
    @MethodSource("privacyResponsePaths")
    void privacyResponseBodiesKeepOnlyAllowlistedSkeleton(String path) {
        // 응답은 ApiResponse envelope 구조(header.code·body)만 남고 원문 필드는 MASK다.
        String raw = "{\"header\":{\"code\":0,\"message\":\"정상 처리\"},"
                + "\"body\":{\"title\":\"RAW_PRIVACY_281_NEVER_LOG\"}}";

        String masked = masker.maskResponse(
                new MockHttpServletRequest("GET", path), jsonResponse(), bytes(raw), false);

        assertThat(masked)
                .isEqualTo("{\"header\":{\"code\":0,\"message\":\"***\"},\"body\":{\"title\":\"***\"}}")
                .doesNotContain("RAW_PRIVACY_281_NEVER_LOG");
    }

    private static Stream<String> privacyResponsePaths() {
        return Stream.of(
                "/a/api/v1/timeline/drafts/task-281",          // draft polling(전체 상태 — SUCCESS만이 아님)
                "/a/api/v1/timeline/daily-records",
                "/a/api/v3/timeline/daily-records/2026-08-11",
                "/a/api/v1/timeline/daily-records/by-id/42",
                "/a/api/v1/timeline/events/42",
                "/api/v1/terms",                               // 약관 목록(공개 조회)
                "/a/api/v1/terms/agreements");                 // 약관 동의 이력
    }

    @Test
    void unparseablePrivacyBodiesFallBackToPlaceholder() {
        // skeleton은 파싱 성공 시에만 — 파싱 불가 body는 형태 정보도 남기지 않는 고정 placeholder다.
        // malformed JSON도 [unavailable...]이 아닌 같은 placeholder
        assertThat(maskRequest("POST", "/a/api/v1/timeline/drafts", "{RAW_PRIVACY_281_NEVER_LOG"))
                .isEqualTo(AccessLogBodyMasker.MASKED_PRIVACY_BODY);

        // 비JSON body도 null이 아닌 placeholder
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
    void draftCreationRequestKeepsStructureAndCollapsesPayload() throws Exception {
        String raw = """
                {"recordDate":"2026-07-08","recordAt":"2026-07-09T09:12:34","recordTimeZone":"Asia/Seoul",
                 "timelineWindow":{"startTime":"2026-07-08T00:00","endTime":"2026-07-09T00:00"},
                 "sourceItems":[{"itemType":"NOTIFICATION","rawId":"0190a1b2-0001-7000-8000-000000000001",
                 "startAt":"2026-07-08T09:05:00","endAt":null,
                 "payload":{"appName":"KakaoTalk","title":"RAW_PRIVACY_312_NEVER_LOG","text":"점심 뭐 먹지"}}]}
                """;

        String masked = maskRequest("POST", "/a/api/v1/timeline/drafts", raw);
        JsonNode json = objectMapper.readTree(masked);

        assertThat(json.get("recordDate").asText()).isEqualTo("2026-07-08");
        assertThat(json.get("recordTimeZone").asText()).isEqualTo("Asia/Seoul");
        assertThat(json.at("/timelineWindow/startTime").asText()).isEqualTo("2026-07-08T00:00");
        assertThat(json.at("/sourceItems/0/itemType").asText()).isEqualTo("NOTIFICATION");
        assertThat(json.at("/sourceItems/0/rawId").asText())
                .isEqualTo("0190a1b2-0001-7000-8000-000000000001");
        assertThat(json.at("/sourceItems/0/endAt").isNull()).isTrue();
        // payload는 내부 필드명 포함 subtree째 붕괴한다.
        assertThat(json.at("/sourceItems/0/payload").asText()).isEqualTo("***");
        assertThat(masked).doesNotContain("RAW_PRIVACY_312_NEVER_LOG", "KakaoTalk", "점심");
    }

    @Test
    void callbackRequestKeepsStatusCodesAndMasksErrorText() {
        // FAILED 진단 축(status·errorCode)은 남고, AI 자유 텍스트 error는 숫자·null이 아니라서 MASK다.
        assertThat(maskRequest("POST", "/s/api/v1/timeline/drafts/task-312/callback",
                "{\"status\":\"FAILED\",\"errorCode\":-1008,\"error\":\"boom RAW_PRIVACY_312_NEVER_LOG\"}"))
                .isEqualTo("{\"status\":\"FAILED\",\"errorCode\":-1008,\"error\":\"***\"}");

        // 숫자·null은 그대로 — 실제 null이 "마스킹된 민감값"처럼 오독되지 않는다. userMemory 원문은 붕괴.
        assertThat(maskRequest("POST", "/s/api/v2/user-memory/updates/task-312/result",
                "{\"status\":\"SUCCESS\",\"userMemory\":{\"tone\":\"소중한 하루\"},\"errorCode\":null,\"error\":null}"))
                .isEqualTo("{\"status\":\"SUCCESS\",\"userMemory\":\"***\",\"errorCode\":null,\"error\":null}");
    }

    @Test
    void pollingResponseKeepsEnvelopeStatusAndNumericError() {
        assertThat(maskGetResponse("/a/api/v1/timeline/drafts/task-312",
                "{\"header\":{\"code\":0,\"message\":\"\"},\"body\":"
                        + "{\"status\":\"PROCESSING\",\"result\":null,\"error\":null,\"elapsedSeconds\":42}}"))
                .isEqualTo("{\"header\":{\"code\":0,\"message\":\"***\"},\"body\":"
                        + "{\"status\":\"PROCESSING\",\"result\":null,\"error\":null,\"elapsedSeconds\":42}}");

        // FAILED의 numeric error code는 진단 축이라 남는다.
        assertThat(maskGetResponse("/a/api/v1/timeline/drafts/task-312",
                "{\"header\":{\"code\":0,\"message\":\"\"},\"body\":{\"status\":\"FAILED\",\"result\":null,\"error\":-1008}}"))
                .isEqualTo("{\"header\":{\"code\":0,\"message\":\"***\"},\"body\":"
                        + "{\"status\":\"FAILED\",\"result\":null,\"error\":-1008}}");
    }

    @Test
    void dailyRecordResponseKeepsStructureAndMasksUserContent() throws Exception {
        String raw = "{\"header\":{\"code\":0,\"message\":\"\"},\"body\":{\"dailyRecordId\":42,"
                + "\"recordDate\":\"2026-07-08\",\"emotionType\":\"HAPPY\",\"events\":[{"
                + "\"timelineEventId\":7,\"eventType\":\"MEAL\",\"startAt\":\"2026-07-08T12:00:00\","
                + "\"endAt\":null,\"title\":\"RAW_TITLE_312_NEVER_LOG\",\"subtitle\":null,"
                + "\"question\":\"오늘 점심 어땠나요?\",\"memo\":\"강남에서 점심\",\"items\":[{"
                + "\"timelineItemId\":9,\"itemType\":\"STAY\",\"rawId\":\"0190a1b2-0001-7000-8000-000000000002\","
                + "\"startAt\":\"2026-07-08T12:00:00\",\"endAt\":null,\"payload\":{\"address\":\"서울 강남구\"}}]}]}}";

        String masked = maskGetResponse("/a/api/v1/timeline/daily-records/2026-07-08", raw);
        JsonNode body = objectMapper.readTree(masked).get("body");

        assertThat(body.get("dailyRecordId").asInt()).isEqualTo(42);
        assertThat(body.get("emotionType").asText()).isEqualTo("HAPPY");
        assertThat(body.at("/events/0/eventType").asText()).isEqualTo("MEAL");
        assertThat(body.at("/events/0/title").asText()).isEqualTo("***");
        // allowlist 밖 필드는 null-여부도 숨긴다(타입 무관 MASK).
        assertThat(body.at("/events/0/subtitle").asText()).isEqualTo("***");
        assertThat(body.at("/events/0/items/0/timelineItemId").asInt()).isEqualTo(9);
        assertThat(body.at("/events/0/items/0/payload").asText()).isEqualTo("***");
        assertThat(masked).doesNotContain("RAW_TITLE_312_NEVER_LOG", "점심", "강남");
    }

    @Test
    void termsResponseMasksTitleAndContentUrlButKeepsStructure() {
        // 응답에 법률 원문은 더 이상 없지만(#320) title·contentUrl은 allowlist 밖이라 그대로 마스크된다 —
        // 실제 URL 값이 log preview에 남지 않는다(종류·버전은 구조 필드로 남아 추적에 충분하다).
        String raw = "{\"header\":{\"code\":0,\"message\":\"\"},\"body\":{\"terms\":[{"
                + "\"termType\":\"TERMS_OF_SERVICE\",\"version\":\"1.0\",\"title\":\"이용약관\","
                + "\"contentUrl\":\"https://laimory.app/terms/terms-of-service/1.0\",\"required\":true,"
                + "\"effectiveAt\":\"2026-09-01T00:00:00\"}]}}";

        assertThat(maskGetResponse("/api/v1/terms", raw))
                .isEqualTo("{\"header\":{\"code\":0,\"message\":\"***\"},\"body\":{\"terms\":[{"
                        + "\"termType\":\"TERMS_OF_SERVICE\",\"version\":\"1.0\",\"title\":\"***\","
                        + "\"contentUrl\":\"***\",\"required\":true,"
                        + "\"effectiveAt\":\"2026-09-01T00:00:00\"}]}}")
                .doesNotContain("laimory.app");
    }

    @Test
    void agreementHistoryResponseMasksContentUrl() {
        String raw = "{\"header\":{\"code\":0,\"message\":\"\"},\"body\":{\"agreements\":[{"
                + "\"termType\":\"PRIVACY_POLICY\",\"version\":\"1.0\",\"title\":\"개인정보 처리방침\","
                + "\"contentUrl\":\"https://laimory.app/terms/privacy-policy/1.0\",\"required\":true,"
                + "\"effectiveAt\":\"2026-09-01T00:00:00\",\"acceptedAt\":\"2026-09-02T09:30:00\"}]}}";

        assertThat(maskGetResponse("/a/api/v1/terms/agreements", raw))
                .isEqualTo("{\"header\":{\"code\":0,\"message\":\"***\"},\"body\":{\"agreements\":[{"
                        + "\"termType\":\"PRIVACY_POLICY\",\"version\":\"1.0\",\"title\":\"***\","
                        + "\"contentUrl\":\"***\",\"required\":true,"
                        + "\"effectiveAt\":\"2026-09-01T00:00:00\",\"acceptedAt\":\"2026-09-02T09:30:00\"}]}}")
                .doesNotContain("laimory.app");
    }

    @Test
    void manualEventCreateRequestAndResponseKeepStructureAndMaskUserText() {
        // #326 수동 Event 생성 — request의 title/subtitle/memo와 이를 echo하는 response에서 원문이 남지 않는다.
        assertThat(maskRequest("POST", "/a/api/v1/timeline/daily-records/2026-07-08/events",
                "{\"eventType\":\"REST\",\"title\":\"RAW_TITLE_326_NEVER_LOG\",\"subtitle\":\"성수동\","
                        + "\"startAt\":\"2026-07-08T14:00:00\",\"endAt\":null,\"memo\":\"RAW_MEMO_326_NEVER_LOG\"}"))
                .isEqualTo("{\"eventType\":\"REST\",\"title\":\"***\",\"subtitle\":\"***\","
                        + "\"startAt\":\"2026-07-08T14:00:00\",\"endAt\":null,\"memo\":\"***\"}")
                .doesNotContain("RAW_TITLE_326_NEVER_LOG", "RAW_MEMO_326_NEVER_LOG", "성수동");

        String rawResponse = "{\"header\":{\"code\":0,\"message\":\"\"},\"body\":{\"timelineEventId\":11,"
                + "\"eventType\":\"REST\",\"startAt\":\"2026-07-08T14:00:00\",\"endAt\":null,"
                + "\"title\":\"RAW_TITLE_326_NEVER_LOG\",\"subtitle\":null,\"question\":null,\"place\":null,"
                + "\"address\":null,\"memo\":\"RAW_MEMO_326_NEVER_LOG\",\"items\":[]}}";
        assertThat(masker.maskResponse(
                new MockHttpServletRequest("POST", "/a/api/v1/timeline/daily-records/2026-07-08/events"),
                jsonResponse(), bytes(rawResponse), false))
                // allowlist 밖 필드는 null-여부도 숨긴다(타입 무관 MASK) — 수동 생성의 question/place/address null 포함.
                .isEqualTo("{\"header\":{\"code\":0,\"message\":\"***\"},\"body\":{\"timelineEventId\":11,"
                        + "\"eventType\":\"REST\",\"startAt\":\"2026-07-08T14:00:00\",\"endAt\":null,"
                        + "\"title\":\"***\",\"subtitle\":\"***\",\"question\":\"***\",\"place\":\"***\","
                        + "\"address\":\"***\",\"memo\":\"***\",\"items\":[]}}")
                .doesNotContain("RAW_TITLE_326_NEVER_LOG", "RAW_MEMO_326_NEVER_LOG");
    }

    @Test
    void manualEventCreatePathDoesNotCaptureOtherMethodsOrEmotionPut() {
        // 같은 날짜 계열 경로의 다른 method는 오매칭하지 않는다 — 감정 PUT body는 enum뿐이라 대상이 아니다.
        assertThat(maskRequest("PUT", "/a/api/v1/timeline/daily-records/2026-07-08/emotion",
                "{\"emotionType\":\"HAPPY\"}"))
                .isEqualTo("{\"emotionType\":\"HAPPY\"}");
        assertThat(maskRequest("GET", "/a/api/v1/timeline/daily-records/2026-07-08/events", "{\"safe\":\"kept\"}"))
                .isEqualTo("{\"safe\":\"kept\"}");
        assertThat(masker.maskResponse(
                new MockHttpServletRequest("GET", "/a/api/v1/timeline/daily-records/2026-07-08/events"),
                jsonResponse(), bytes("{\"safe\":\"kept\"}"), false))
                .isEqualTo("{\"safe\":\"kept\"}");
    }

    @Test
    void shapeGuardMasksNonStructuralTextEvenInSafeFields() {
        // 클라 버그로 구조 필드에 원문이 실려도 공백·비ASCII·장문(>64자)은 통과하지 못한다.
        assertThat(maskRequest("POST", "/a/api/v1/timeline/drafts",
                "{\"rawId\":\"오늘 강남 RAW_PRIVACY_312_NEVER_LOG\",\"recordDate\":\"2026-07-08\","
                        + "\"sourceRawIds\":[\"0190a1b2-0001-7000-8000-000000000001\",\"두 단어\"],"
                        + "\"status\":\"" + "a".repeat(65) + "\"}"))
                .isEqualTo("{\"rawId\":\"***\",\"recordDate\":\"2026-07-08\","
                        + "\"sourceRawIds\":[\"0190a1b2-0001-7000-8000-000000000001\",\"***\"],\"status\":\"***\"}");
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
                "{\"agreements\":[{\"termType\":\"TERMS_OF_SERVICE\",\"version\":\"1.0\"}]}"))
                .contains("\"termType\":\"TERMS_OF_SERVICE\"")
                .contains("\"version\":\"1.0\"");
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

    private String maskGetResponse(String path, String body) {
        return masker.maskResponse(new MockHttpServletRequest("GET", path), jsonResponse(), bytes(body), false);
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
