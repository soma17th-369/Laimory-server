package com.laimory.server.terms.service;

import com.laimory.server.terms.TermType;

/**
 * 현재 약관 문서의 content 제외 요약 — enforcement/readiness와 동의 버전 검증이 쓰는 조회 단위다.
 *
 * <p>LOGIN gate는 모든 {@code /a/api} 요청에서 현재 문서를 조회하므로 {@code LONGTEXT content}를
 * 요청마다 DB에서 끌어오지 않도록 ID·mapping metadata·version만 담는다. 원문 전체는 공개 조회·이력
 * 조회에서만 읽는다.
 */
public record TermDocumentSummary(
        Long termDocumentId,
        TermType termType,
        String stage,
        Boolean required,
        String version
) {
}
