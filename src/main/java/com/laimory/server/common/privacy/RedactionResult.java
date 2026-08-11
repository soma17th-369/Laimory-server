package com.laimory.server.common.privacy;

import java.util.Map;

/**
 * text redaction 결과. 치환된 text와 유형별 실제 치환 건수만 담는다.
 *
 * <p>원문·hash·preview는 만들지 않는다. bounded 치환에서 뒤쪽이 잘려도 occurrence는
 * 치환 시점의 탐지 건수를 유지한다(관측용 집계이지 출력 내 token 수가 아니다).
 */
public record RedactionResult(String text, Map<RedactionType, Integer> occurrences) {

    public RedactionResult {
        occurrences = Map.copyOf(occurrences);
    }

    public int count(RedactionType type) {
        return occurrences.getOrDefault(type, 0);
    }

    public int total() {
        return occurrences.values().stream().mapToInt(Integer::intValue).sum();
    }
}
