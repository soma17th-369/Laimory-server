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

/** 제한된 JSON body를 access log용 text preview로 만들고 비밀 필드를 재귀적으로 제거한다. */
final class AccessLogBodyMasker {

    static final int CAPTURE_LIMIT_BYTES = 64 * 1024;
    static final String MASKED_AUTH_BODY = "[masked auth body]";
    static final String UNHANDLED_EXCEPTION_BODY = "[unavailable: unhandled exception]";

    private static final int MAX_LOGGED_CHARS = 8192;
    private static final String MASK = "***";
    private static final String TOO_LARGE = "[too large: body exceeds 65536 bytes]";
    private static final String UNAVAILABLE_JSON = "[unavailable: invalid or unmaskable JSON]";

    private static final Pattern AUTH_BODY_PATH =
            Pattern.compile("^/api/v\\d+/auth/(token|refresh|logout)$");
    private static final Set<String> EXACT_SECRET_NAMES =
            Set.of("appcode", "appverifier", "uploadurl");
    private static final List<String> CONTAINED_SECRET_NAMES =
            List.of("password", "secret", "token", "credential", "authorization");

    private final ObjectMapper objectMapper;

    AccessLogBodyMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String maskRequest(HttpServletRequest request, byte[] body, boolean overflowed) {
        if (body.length == 0 || !isJson(request.getContentType())) {
            return null;
        }
        if (AUTH_BODY_PATH.matcher(request.getRequestURI()).matches()) {
            return MASKED_AUTH_BODY;
        }
        return maskJson(body, request.getContentLengthLong(), overflowed);
    }

    String maskResponse(HttpServletResponse response, byte[] body, boolean overflowed) {
        if (body.length == 0 || !isJson(response.getContentType())) {
            return null;
        }
        return maskJson(body, contentLength(response), overflowed);
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
