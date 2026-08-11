package com.laimory.server.common.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * 제한된 JSON body를 access log용 text preview로 만들고 비밀 필드를 재귀적으로 제거한다.
 * 사용자 사생활 원문을 통째로 담는 지정 method+path는 body 전체를 고정 placeholder로 치환한다.
 */
final class AccessLogBodyMasker {

    static final int CAPTURE_LIMIT_BYTES = 512 * 1024;
    static final String MASKED_AUTH_BODY = "[masked auth body]";
    static final String MASKED_PRIVACY_BODY = "[masked privacy body]";
    static final String UNHANDLED_EXCEPTION_BODY = "[unavailable: unhandled exception]";

    static final int MAX_LOGGED_CHARS = 65536;
    private static final String MASK = "***";
    // 상한을 바꿔도 문구가 거짓이 되지 않도록 상수에서 파생한다(하드코딩 금지).
    private static final String TOO_LARGE = "[too large: body exceeds " + CAPTURE_LIMIT_BYTES + " bytes]";
    private static final String UNAVAILABLE_JSON = "[unavailable: invalid or unmaskable JSON]";

    private static final Pattern AUTH_BODY_PATH =
            Pattern.compile("^/api/v\\d+/auth/(token|refresh|logout)$");

    // 사용자 원문(draft 항목·Event·memo·AI result·User Memory result·FAILED callback error)을 담는
    // request body. drafts는 photo-uploads(presign)를 제외하기 위해 정확히 끝나는 경로만 매칭한다.
    private static final List<PrivacyBodyPath> PRIVACY_REQUEST_PATHS = List.of(
            new PrivacyBodyPath("POST", Pattern.compile("^/a/api/v\\d+/timeline/drafts$")),
            new PrivacyBodyPath("PATCH", Pattern.compile("^/a/api/v\\d+/timeline/events/[^/]+$")),
            new PrivacyBodyPath("PUT", Pattern.compile("^/a/api/v\\d+/timeline/events/[^/]+/memo$")),
            new PrivacyBodyPath("POST", Pattern.compile("^/s/api/v\\d+/timeline/drafts/[^/]+/result$")),
            new PrivacyBodyPath("POST", Pattern.compile("^/s/api/v\\d+/timeline/drafts/[^/]+/callback$")),
            new PrivacyBodyPath("POST", Pattern.compile("^/s/api/v\\d+/user-memory/updates/[^/]+/result$")));

    // 사용자 원문을 echo하는 response body — draft polling(전체 상태)·daily-record 조회·Event 단건.
    // AI input(GET /s/api/.../drafts/{taskId}/input)은 저장 시점에 치환된 서버간 응답이라 대상이 아니다.
    private static final List<PrivacyBodyPath> PRIVACY_RESPONSE_PATHS = List.of(
            new PrivacyBodyPath("GET", Pattern.compile("^/a/api/v\\d+/timeline/drafts/[^/]+$")),
            new PrivacyBodyPath("GET", Pattern.compile("^/a/api/v\\d+/timeline/daily-records$")),
            new PrivacyBodyPath("GET", Pattern.compile("^/a/api/v\\d+/timeline/daily-records/[^/]+$")),
            new PrivacyBodyPath("GET", Pattern.compile("^/a/api/v\\d+/timeline/daily-records/by-id/[^/]+$")),
            new PrivacyBodyPath("GET", Pattern.compile("^/a/api/v\\d+/timeline/events/[^/]+$")));
    private static final Set<String> EXACT_SECRET_NAMES =
            Set.of("appcode", "appverifier", "uploadurl", "firebaseinstallationid");
    private static final List<String> CONTAINED_SECRET_NAMES =
            List.of("password", "secret", "token", "credential", "authorization");

    private final ObjectMapper objectMapper;

    AccessLogBodyMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String maskRequest(HttpServletRequest request, byte[] body, boolean overflowed) {
        // path 판정이 body 검사보다 먼저다 — 대상 경로는 empty·비JSON·malformed·oversize여도
        // 파싱을 시도하지 않고 같은 고정 placeholder로 확정해 원문 유출 경로를 남기지 않는다.
        if (AUTH_BODY_PATH.matcher(request.getRequestURI()).matches()) {
            return MASKED_AUTH_BODY;
        }
        if (matchesAny(PRIVACY_REQUEST_PATHS, request)) {
            return MASKED_PRIVACY_BODY;
        }
        if (body.length == 0 || !isJson(request.getContentType())) {
            return null;
        }
        return maskJson(body, request.getContentLengthLong(), overflowed);
    }

    String maskResponse(HttpServletRequest request, HttpServletResponse response, byte[] body, boolean overflowed) {
        if (matchesAny(PRIVACY_RESPONSE_PATHS, request)) {
            return MASKED_PRIVACY_BODY;
        }
        if (body.length == 0 || !isJson(response.getContentType())) {
            return null;
        }
        return maskJson(body, contentLength(response), overflowed);
    }

    private static boolean matchesAny(List<PrivacyBodyPath> rules, HttpServletRequest request) {
        for (PrivacyBodyPath rule : rules) {
            if (rule.matches(request)) {
                return true;
            }
        }
        return false;
    }

    private String maskJson(byte[] body, long declaredLength, boolean overflowed) {
        if (declaredLength > CAPTURE_LIMIT_BYTES || overflowed) {
            return TOO_LARGE;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null) {
                return UNAVAILABLE_JSON;
            }
            String compactJson = objectMapper.writeValueAsString(maskNode(root));
            return LogSanitizer.sanitize(compactJson, MAX_LOGGED_CHARS);
        } catch (IOException | IllegalArgumentException e) {
            return UNAVAILABLE_JSON;
        }
    }

    private JsonNode maskNode(JsonNode node) {
        if (node.isTextual()) {
            return containsIgnoreCase(node.textValue(), "x-amz-") ? TextNode.valueOf(MASK) : node;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            object.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                JsonNode value = object.get(fieldName);
                object.set(fieldName, isSecretField(fieldName) ? TextNode.valueOf(MASK) : maskNode(value));
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, maskNode(array.get(index)));
            }
        }
        return node;
    }

    private static boolean isSecretField(String fieldName) {
        String normalized = fieldName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return EXACT_SECRET_NAMES.contains(normalized)
                || CONTAINED_SECRET_NAMES.stream().anyMatch(normalized::contains);
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.includes(mediaType)
                    || mediaType.getSubtype().toLowerCase(Locale.ROOT).endsWith("+json");
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    /** method+path 정확 판정 규칙. method는 대소문자 구분 — 비표준 소문자 method는 라우팅 자체가 안 된다. */
    private record PrivacyBodyPath(String method, Pattern path) {
        boolean matches(HttpServletRequest request) {
            return method.equals(request.getMethod()) && path.matcher(request.getRequestURI()).matches();
        }
    }

    private static long contentLength(HttpServletResponse response) {
        String value = response.getHeader("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
