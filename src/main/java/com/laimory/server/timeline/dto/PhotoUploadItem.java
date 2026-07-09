package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * presign 요청에 중첩되는 업로드 입력 요소(요청 바디 내부 요소라 방향 접미사 대신 도메인 이름).
 *
 * <p>{@code contentType}/{@code size}는 발급 전 검증(허용 타입·크기 한도)에 쓰고, {@code size}는 presigned PUT
 * 서명의 content-length로 바인딩해 S3가 업로드 시점에 정확한 크기를 강제하게 한다(크기 우회 방지).
 */
public record PhotoUploadItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "image/jpeg",
                allowableValues = {"image/jpeg", "image/png", "image/webp"},
                description = "사진 MIME 타입. image/jpeg·image/png·image/webp만 허용(image/jpg 아님) — 그 외는 ERROR_1007.")
        String contentType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1048576",
                description = "파일 크기(바이트). 0 초과, 장당 최대 5MB(=5,242,880 bytes) — 초과 시 ERROR_1005. "
                        + "발급되는 presigned PUT의 Content-Length에 바인딩돼 S3가 업로드 시점에 정확한 크기를 강제한다.")
        Long size
) {
}
