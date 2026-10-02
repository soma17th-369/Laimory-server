package com.laimory.server.inquiry.dto;

/** 관리자 문의 상세의 첨부 한 장 — {@code viewUrl}은 앱 소유자와 같은 무서명 CDN URL이다(#529). */
public record AdminInquiryAttachmentResponse(String filename, String viewUrl) {
}
