package com.laimory.server.inquiry.dto;

import com.laimory.server.inquiry.entity.Inquiry;
import java.time.LocalDateTime;

/** 관리자 문의 항목 — subject는 싣지 않는다. 관리자가 회신에 필요한 것은 주소·제목·내용·처리 여부뿐이다. */
public record AdminInquiryResponse(Long inquiryId, String email, String title, String description,
                                   LocalDateTime answeredAt, LocalDateTime createdAt) {

    public static AdminInquiryResponse from(Inquiry inquiry) {
        return new AdminInquiryResponse(inquiry.getInquiryId(), inquiry.getEmail(), inquiry.getTitle(),
                inquiry.getDescription(), inquiry.getAnsweredAt(), inquiry.getCreatedAt());
    }
}
