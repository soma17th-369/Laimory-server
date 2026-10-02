package com.laimory.server.terms.dto;

import com.laimory.server.terms.TermType;
import java.util.List;

/** 관리자 약관 이력의 종류별 묶음 — {@code documents}는 숫자 버전 내림차순, {@code current}는 그 첫 문서(없으면 null). */
public record AdminTermGroupResponse(TermType termType, AdminTermDocumentResponse current,
                                     List<AdminTermDocumentResponse> documents) {
}
