package com.laimory.server.timeline.dto;

import java.util.List;

/**
 * presigned PUT URL 발급 요청 바디. 클라이언트가 올릴 사진들의 메타(타입·크기)만 보낸다(바이트는 S3로 직접 PUT).
 */
public record PhotoUploadCreateRequest(
        List<PhotoUploadItem> photos
) {
}
