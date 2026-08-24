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
 * 사용자 사생활 원문을 담는 지정 method+path는 allowlist 구조 필드만 값을 남기는 skeleton으로
 * 마스킹하고(기본 마스크 — 목록 밖 필드는 타입 무관 제거), 파싱할 수 없는 body는 고정
 * placeholder로 폴백해 원문 유출 경로를 남기지 않는다.
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
    // 수동 Event 생성 POST(#326)의 title/subtitle/memo도 사용자 원문이다.
    private static final List<PrivacyBodyPath> PRIVACY_REQUEST_PATHS = List.of(
            new PrivacyBodyPath("POST", Pattern.compile("^/a/api/v\\d+/timeline/drafts$")),
            new PrivacyBodyPath("PATCH", Pattern.compile("^/a/api/v\\d+/timeline/events/[^/]+$")),
            new PrivacyBodyPath("PUT", Pattern.compile("^/a/api/v\\d+/timeline/events/[^/]+/memo$")),
            new PrivacyBodyPath("POST", Pattern.compile("^/a/api/v\\d+/timeline/daily-records/[^/]+/events$")),
            new PrivacyBodyPath("POST", Pattern.compile("^/s/api/v\\d+/timeline/drafts/[^/]+/result$")),
            new PrivacyBodyPath("POST", Pattern.compile("^/s/api/v\\d+/timeline/drafts/[^/]+/callback$")),
            new PrivacyBodyPath("POST", Pattern.compile("^/s/api/v\\d+/user-memory/updates/[^/]+/result$")));

    // 사용자 원문을 echo하는 response body — draft polling(전체 상태)·daily-record 조회·Event 단건·
    // 약관 두 GET. 약관 응답은 #320 이후 법률 원문 대신 page URL만 담지만 skeleton을 해제하지 않는다 —
    // 제목·URL은 allowlist 밖이라 마스크되고 추적에 필요한 종류·버전만 구조 필드로 남는다. URL이 필요하면
    // 그 종류·버전으로 term_documents.content_url을 읽는다(로그가 원본이 아니다).
    // 동의 POST request에는 원문이 없어 기존 field-level 규칙 유지.
    // AI input(GET /s/api/.../drafts/{taskId}/input)은 저장 시점에 치환된 서버간 응답이라 대상이 아니다.
    // 수동 Event 생성 POST response는 입력 title/subtitle/memo를 echo하므로 request와 함께 대상이다.
    private static final List<PrivacyBodyPath> PRIVACY_RESPONSE_PATHS = List.of(
            new PrivacyBodyPath("GET", Pattern.compile("^/a/api/v\\d+/timeline/drafts/[^/]+$")),
            new PrivacyBodyPath("GET", Pattern.compile("^/a/api/v\\d+/timeline/daily-records$")),
            new PrivacyBodyPath("GET", Pattern.compile("^/a/api/v\\d+/timeline/daily-records/[^/]+$")),
            new PrivacyBodyPath("GET", Pattern.compile("^/a/api/v\\d+/timeline/daily-records/by-id/[^/]+$")),
            new PrivacyBodyPath("GET", Pattern.compile("^/a/api/v\\d+/timeline/events/[^/]+$")),
            new PrivacyBodyPath("POST", Pattern.compile("^/a/api/v\\d+/timeline/daily-records/[^/]+/events$")),
            new PrivacyBodyPath("GET", Pattern.compile("^/api/v\\d+/terms$")),
            new PrivacyBodyPath("GET", Pattern.compile("^/a/api/v\\d+/terms/agreements$")));
    private static final Set<String> EXACT_SECRET_NAMES =
            Set.of("appcode", "appverifier", "uploadurl", "firebaseinstallationid");
    private static final List<String> CONTAINED_SECRET_NAMES =
            List.of("password", "secret", "token", "credential", "authorization");

    // privacy 경로 skeleton allowlist(#312) — 여기 명시된 구조 필드만 값을 남기고 목록 밖 필드는
    // 타입 무관 subtree째 MASK다. 새 DTO 필드의 기본이 마스크라 목록 갱신 누락이 유출로 새지 않는다.
    private static final Set<String> SKELETON_SAFE_FIELDS = Set.of(
            // draft 생성·Event 수정 request envelope
            "recorddate", "recordat", "recordtimezone", "timelinewindow", "starttime", "endtime",
            "sourceitems", "itemtype", "rawid", "startat", "endat", "photostoadd",
            // AI result·상태 전이
            "events", "eventtype", "sourcerawids", "status", "elapsedseconds",
            // 조회 response 구조(ApiResponse envelope 포함 — message는 제외)
            "header", "code", "body", "result", "timelines", "dailyrecordid", "emotiontype",
            "timelineeventid", "items", "timelineitemid",
            // 약관 구조(title·contentUrl은 제외 — 값 자체를 로그에 남기지 않는다)
            "terms", "agreements", "termtype", "version", "required", "effectiveat", "acceptedat");

    // 폴링 response의 error는 numeric code지만 callback request의 error는 사용자 원문이 섞일 수 있는
    // 자유 텍스트다(수신 후 폐기 계약) — 같은 이름의 이중 의미라 숫자·null만 남긴다. errorCode는
    // StrictErrorCodeDeserializer 적용 전의 wire 원문이므로 같은 규칙으로 텍스트 가능성을 차단한다.
    private static final Set<String> SKELETON_NUMERIC_ONLY_FIELDS = Set.of("error", "errorcode");

    // allowlist 필드의 텍스트 값 shape guard — 현 계약의 구조 값(enum·UUID·ISO 시각·ZoneId·버전
    // 문자열)만 통과한다. 클라 버그로 구조 필드에 원문이 실려도 공백·비ASCII·장문은 남지 않는다.
    private static final Pattern SKELETON_STRUCTURAL_TEXT = Pattern.compile("[A-Za-z0-9_\\-.:+/]{1,64}");

    private final ObjectMapper objectMapper;

    AccessLogBodyMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String maskRequest(HttpServletRequest request, byte[] body, boolean overflowed) {
        // path 판정이 body 검사보다 먼저다 — auth는 항상 placeholder, privacy는 skeleton 전용
        // 경로로 보내 empty·비JSON·malformed·oversize가 일반 마스킹 규칙으로 새지 않게 한다.
        if (AUTH_BODY_PATH.matcher(request.getRequestURI()).matches()) {
            return MASKED_AUTH_BODY;
        }
        if (matchesAny(PRIVACY_REQUEST_PATHS, request)) {
            return maskPrivacyBody(body, request.getContentLengthLong(), overflowed);
        }
        if (body.length == 0 || !isJson(request.getContentType())) {
            return null;
        }
        return maskJson(body, request.getContentLengthLong(), overflowed);
    }

    String maskResponse(HttpServletRequest request, HttpServletResponse response, byte[] body, boolean overflowed) {
        if (matchesAny(PRIVACY_RESPONSE_PATHS, request)) {
            return maskPrivacyBody(body, contentLength(response), overflowed);
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

    /**
     * privacy 경로 body의 allowlist skeleton. 파싱 성공 시에만 구조를 남기고, 파싱할 수 없는
     * body(empty·oversize·malformed·비JSON)는 형태 정보도 남기지 않도록 고정 placeholder로 폴백한다.
     */
    private String maskPrivacyBody(byte[] body, long declaredLength, boolean overflowed) {
        if (body.length == 0 || declaredLength > CAPTURE_LIMIT_BYTES || overflowed) {
            return MASKED_PRIVACY_BODY;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null) {
                return MASKED_PRIVACY_BODY;
            }
            String compactJson = objectMapper.writeValueAsString(skeletonNode(root));
            return LogSanitizer.sanitize(compactJson, MAX_LOGGED_CHARS);
        } catch (IOException | IllegalArgumentException e) {
            return MASKED_PRIVACY_BODY;
        }
    }

    private JsonNode skeletonNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            object.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                object.set(fieldName, skeletonValue(fieldName, object.get(fieldName)));
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, skeletonNode(array.get(index)));
            }
            return array;
        }
        return skeletonScalar(node);
    }

    private JsonNode skeletonValue(String fieldName, JsonNode value) {
        String normalized = normalizeFieldName(fieldName);
        if (SKELETON_NUMERIC_ONLY_FIELDS.contains(normalized)) {
            // null까지 마스크하면 실제 null이 "마스킹된 민감값"처럼 오독된다 — 숫자·null만 유지.
            return value.isNumber() || value.isNull() ? value : TextNode.valueOf(MASK);
        }
        if (!SKELETON_SAFE_FIELDS.contains(normalized)) {
            return TextNode.valueOf(MASK);
        }
        return skeletonNode(value);
    }

    private static JsonNode skeletonScalar(JsonNode node) {
        if (node.isTextual() && !SKELETON_STRUCTURAL_TEXT.matcher(node.textValue()).matches()) {
            return TextNode.valueOf(MASK);
        }
        return node;
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
        String normalized = normalizeFieldName(fieldName);
        return EXACT_SECRET_NAMES.contains(normalized)
                || CONTAINED_SECRET_NAMES.stream().anyMatch(normalized::contains);
    }

    private static String normalizeFieldName(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
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
