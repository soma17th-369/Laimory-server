package com.laimory.server.inquiry.dto;

import java.util.List;

/** 첨부 presigned PUT URL 발급 응답 바디. 요청 attachments와 같은 순서로 발급 결과를 담는다. */
public record InquiryAttachmentUploadCreateResponse(
        List<InquiryAttachmentUploadResponse> uploads
) {
}
