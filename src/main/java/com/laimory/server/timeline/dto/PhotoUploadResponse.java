package com.laimory.server.timeline.dto;

/**
 * presign 응답에 중첩되는 업로드 발급 결과 요소.
 *
 * <p>{@code filename}은 클라이언트가 draft-create의 PHOTO payload에 그대로 담아 보낼 최소 식별자
 * ({@code {uuidv7}.{ext}})이고, {@code uploadUrl}은 그 사진을 S3에 PUT할 presigned URL이다.
 */
public record PhotoUploadResponse(
        String filename,
        String uploadUrl
) {
}
