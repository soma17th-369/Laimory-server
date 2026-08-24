package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/** Event PATCH 또는 수동 Event 생성 POST로 추가하는 PHOTO의 클라이언트 입력 payload. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateTimelineEventPhotoPayloadRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                example = "0190a1b2-0002-7000-8000-000000000002.jpg",
                description = "사진 업로드 URL 발급 API가 반환한 파일명({uuidv7}.{jpg|png|webp}).")
        String filename,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                example = "content://media/external/images/media/1001",
                description = "클라이언트 기기의 로컬 사진 URI. 서버는 해석하지 않고 저장·echo한다.")
        String clientPhotoUri,
        @Schema(example = "37.5665", description = "위도(십진도). 선택.")
        Double latitude,
        @Schema(example = "126.9780", description = "경도(십진도). 선택.")
        Double longitude
) {
}
