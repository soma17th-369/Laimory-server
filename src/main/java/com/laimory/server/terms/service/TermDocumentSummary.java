package com.laimory.server.terms.service;

import com.laimory.server.terms.TermType;

/**
 * 현재 약관 문서의 식별 요약 — enforcement/readiness와 동의 버전 검증이 쓰는 조회 단위다.
 *
 * <p>필수 약관 gate는 모든 비면제 {@code /a/api} 요청에서 현재 문서를 조회하므로 판정에 필요한
 * ID·종류·버전만 담는다(제목·효력일은 이 경로의 소비자가 없다).
 */
public record TermDocumentSummary(
        Long termDocumentId,
        TermType termType,
        String version
) {
}
