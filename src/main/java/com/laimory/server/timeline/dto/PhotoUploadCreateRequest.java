package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * presigned PUT URL 발급 요청 바디. 클라이언트가 올릴 사진들의 메타(타입·크기)만 보낸다(바이트는 S3로 직접 PUT).
 */
public record PhotoUploadCreateRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "업로드할 사진 메타 목록. 비어 있으면 안 되고, 요청당 최대 20장 — 초과 시 ERROR_1004.")
        List<PhotoUploadItem> photos
) {
}
