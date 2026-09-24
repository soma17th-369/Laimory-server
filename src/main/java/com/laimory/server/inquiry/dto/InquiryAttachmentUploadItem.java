package com.laimory.server.inquiry.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 첨부 presign 요청에 중첩되는 업로드 입력 요소. 사진 업로드와 같은 계약이다 — {@code size}는 presigned PUT
 * 서명의 Content-Length에 바인딩돼 S3가 업로드 시점에 정확한 크기를 강제한다.
 */
public record InquiryAttachmentUploadItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "image/jpeg",
                allowableValues = {"image/jpeg", "image/png", "image/webp"},
                description = "첨부 MIME 타입. image/jpeg·image/png·image/webp만 허용 — 그 외는 -1007.")
        String contentType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1048576",
                description = "파일 크기(바이트). 0 초과, 장당 최대 5MB — 초과 시 -1005. "
                        + "발급되는 presigned PUT의 Content-Length에 바인딩된다.")
        Long size
) {
}
