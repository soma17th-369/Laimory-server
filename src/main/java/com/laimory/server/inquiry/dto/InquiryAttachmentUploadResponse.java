package com.laimory.server.inquiry.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 첨부 presign 응답 요소. {@code filename}은 접수 요청의 {@code attachmentFilenames}에 그대로 담아 보낼
 * 식별자({@code {uuidv7}.{ext}})이고, {@code uploadUrl}은 그 첨부를 S3에 PUT할 presigned URL이다.
 */
public record InquiryAttachmentUploadResponse(
        @Schema(description = "접수 요청 attachmentFilenames에 그대로 회신할 파일명",
                example = "0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.jpg") String filename,
        @Schema(description = "첨부를 PUT할 presigned URL(유효시간 내 1회 업로드)") String uploadUrl
) {
}
