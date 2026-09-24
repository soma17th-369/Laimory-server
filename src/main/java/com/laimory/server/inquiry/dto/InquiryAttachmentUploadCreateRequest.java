package com.laimory.server.inquiry.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 문의 첨부 presigned PUT URL 발급 요청 바디 — 올릴 사진의 메타(타입·크기)만 보낸다(바이트는 S3로 직접 PUT). */
public record InquiryAttachmentUploadCreateRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "업로드할 첨부 메타 목록. 비어 있으면 안 되고, 요청당 최대 3장 — 초과 시 -1004.")
        List<InquiryAttachmentUploadItem> attachments
) {
}
