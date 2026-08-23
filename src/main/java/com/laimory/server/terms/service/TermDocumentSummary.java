package com.laimory.server.terms.service;

import com.laimory.server.terms.TermType;

/**
 * 현재 약관 문서의 식별 요약 — enforcement/readiness와 동의 버전 검증이 쓰는 조회 단위다.
 *
 * <p>LOGIN gate는 모든 {@code /a/api} 요청에서 현재 문서를 조회하므로 판정에 필요한 ID·종류·버전만
 * 담는다(제목·효력일은 이 경로의 소비자가 없다). 단계·필수 여부·화면 순서는 {@link TermType} mapping이
 * 권위라 이 요약에 담지 않는다.
 */
public record TermDocumentSummary(
        Long termDocumentId,
        TermType termType,
        String version
) {
}
