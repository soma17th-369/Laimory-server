package com.laimory.server.timeline.dto;

import java.util.List;

/**
 * presigned PUT URL 발급 응답 바디. 요청 photos와 같은 순서로 발급 결과를 담는다.
 */
public record PhotoUploadCreateResponse(
        List<PhotoUploadResponse> uploads
) {
}
