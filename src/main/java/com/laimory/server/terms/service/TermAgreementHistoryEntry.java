package com.laimory.server.terms.service;

import com.laimory.server.terms.entity.TermAgreement;
import com.laimory.server.terms.entity.TermDocument;

/** 동의 이력 한 건 + 동의한 불변 문서 행 — repository join(JPQL constructor expression) 결과다. */
public record TermAgreementHistoryEntry(TermAgreement agreement, TermDocument document) {
}
