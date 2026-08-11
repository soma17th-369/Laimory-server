package com.laimory.server.common.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * v1 금지 유형을 고정 token으로 치환하는 공용 redactor. 저장(draft/AI 결과/User Memory)과
 * AI 전달 경계가 같은 인스턴스를 공유하며 흐름별 정책 복제를 두지 않는다.
 *
 * <p>상태가 없어 thread-safe다. 원문·매치 문자열을 예외·로그·metric에 담지 않는다.
 */
@Component
public class PrivacyRedactor {

    private static final JsonNodeFactory NODE_FACTORY = JsonNodeFactory.instance;

    /** null-safe text redaction — null 입력이면 null text와 빈 occurrence를 반환한다. */
    public RedactionResult redactText(String text) {
        if (text == null) {
            return new RedactionResult(null, Map.of());
        }
        TextRedaction redaction = redactInternal(text);
        return new RedactionResult(redaction.text(), redaction.counts());
    }

    /**
     * 전체 치환 뒤 결과가 {@code maxLength}를 넘으면 뒤쪽 일반 text를 잘라 맞춘다.
     * 절단 경계가 token literal 내부면 그 token 시작 앞에서 끊는다 — token을 중간에 자르거나
     * 원문으로 fallback하지 않으며 결과는 항상 {@code maxLength} 이하다.
     */
    public RedactionResult redactText(String text, int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        if (text == null) {
            return new RedactionResult(null, Map.of());
        }
        TextRedaction redaction = redactInternal(text);
        return new RedactionResult(truncateTokenAware(redaction, maxLength), redaction.counts());
    }

    public JsonRedactionResult redactTree(JsonNode node) {
        return redactTree(node, Set.of());
    }

    /**
     * object/array를 재귀하며 textual leaf만 치환한 새 tree를 반환한다. field name·number·
     * boolean·null은 보존하고 입력 node는 변형하지 않는다(Hibernate 관리 엔티티 필드일 수 있다).
     *
     * @param excludedFieldNames 이 이름을 가진 field의 string 값은 원문 그대로 통과시킨다
     *                           (storage 경계의 {@code clientPhotoUri} 보존용)
     */
    public JsonRedactionResult redactTree(JsonNode node, Set<String> excludedFieldNames) {
        EnumMap<RedactionType, Integer> counts = new EnumMap<>(RedactionType.class);
        JsonNode redacted = node == null ? null : redactNode(node, excludedFieldNames, counts);
        return new JsonRedactionResult(redacted, counts);
    }

    private JsonNode redactNode(JsonNode node, Set<String> excludedFieldNames,
            EnumMap<RedactionType, Integer> counts) {
        if (node.isTextual()) {
            TextRedaction redaction = redactInternal(node.textValue());
            redaction.counts().forEach((type, count) -> counts.merge(type, count, Integer::sum));
            return redaction.text().equals(node.textValue()) ? node : TextNode.valueOf(redaction.text());
        }
        if (node.isObject()) {
            ObjectNode redacted = NODE_FACTORY.objectNode();
            node.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                boolean excluded = value.isTextual() && excludedFieldNames.contains(entry.getKey());
                redacted.set(entry.getKey(), excluded ? value : redactNode(value, excludedFieldNames, counts));
            });
            return redacted;
        }
        if (node.isArray()) {
            ArrayNode redacted = NODE_FACTORY.arrayNode(node.size());
            node.forEach(element -> redacted.add(redactNode(element, excludedFieldNames, counts)));
            return redacted;
        }
        // number/boolean/null/binary leaf는 불변이라 같은 인스턴스를 재사용해도 입력이 변형되지 않는다.
        return node;
    }

    private static TextRedaction redactInternal(String text) {
        List<RedactionSpan> spans = PiiDetectors.detect(text);
        EnumMap<RedactionType, Integer> counts = new EnumMap<>(RedactionType.class);
        if (spans.isEmpty()) {
            return new TextRedaction(text, counts, List.of());
        }
        StringBuilder output = new StringBuilder(text.length());
        List<int[]> tokenSpans = new ArrayList<>(spans.size());
        int cursor = 0;
        for (RedactionSpan span : spans) {
            output.append(text, cursor, span.start());
            int tokenStart = output.length();
            if (span.protectedLiteral()) {
                output.append(text, span.start(), span.end());
            } else {
                output.append(span.type().token());
                counts.merge(span.type(), 1, Integer::sum);
            }
            // 보호된 기존 placeholder도 출력에서는 token literal이므로 절단 금지 구간에 함께 올린다.
            tokenSpans.add(new int[] {tokenStart, output.length()});
            cursor = span.end();
        }
        output.append(text, cursor, text.length());
        return new TextRedaction(output.toString(), counts, tokenSpans);
    }

    private static String truncateTokenAware(TextRedaction redaction, int maxLength) {
        String text = redaction.text();
        if (text.length() <= maxLength) {
            return text;
        }
        int cut = maxLength;
        for (int[] tokenSpan : redaction.tokenSpans()) {
            if (tokenSpan[0] < cut && cut < tokenSpan[1]) {
                cut = tokenSpan[0];
                break;
            }
        }
        return text.substring(0, cut);
    }

    /** 내부 전용 중간 결과 — 출력 내 token literal 구간은 bounded 절단에서만 쓴다. */
    private record TextRedaction(String text, EnumMap<RedactionType, Integer> counts, List<int[]> tokenSpans) {
    }
}
