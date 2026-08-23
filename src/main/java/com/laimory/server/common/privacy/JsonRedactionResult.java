package com.laimory.server.common.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * JsonNode redaction 결과. 입력을 변형하지 않고 새로 만든 tree와 textual leaf 전체의
 * 유형별 치환 건수 합산을 담는다.
 */
public record JsonRedactionResult(JsonNode node, Map<RedactionType, Integer> occurrences) {

    public JsonRedactionResult {
        occurrences = Map.copyOf(occurrences);
    }

    public int count(RedactionType type) {
        return occurrences.getOrDefault(type, 0);
    }

    public int total() {
        return occurrences.values().stream().mapToInt(Integer::intValue).sum();
    }
}
