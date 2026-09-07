package com.laimory.server.terms.service;

import com.laimory.server.terms.TermType;

/**
 * 약관 문서의 식별 요약 — 동의 등록의 현재 버전 검증과 initializer의 미동의 판정이 쓰는
 * 조회 단위다. 복합 key인 종류·버전만 담는다.
 */
public record TermDocumentSummary(
        TermType termType,
        String version
) {
}
