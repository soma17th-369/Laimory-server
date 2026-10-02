package com.laimory.server.inquiry.dto;

import java.util.List;

/** 관리자 문의 상세 — 목록 항목과 첨부(요청 순서). */
public record AdminInquiryDetailResponse(AdminInquiryResponse inquiry, List<AdminInquiryAttachmentResponse> attachments) {
}
