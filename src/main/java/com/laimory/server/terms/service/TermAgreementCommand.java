package com.laimory.server.terms.service;

import com.laimory.server.terms.TermType;

/** 동의 요청 한 항목 — 회원이 동의한 약관 종류와 그 버전 문자열(현재 버전과 정확히 일치해야 한다). */
public record TermAgreementCommand(TermType termType, String version) {
}
