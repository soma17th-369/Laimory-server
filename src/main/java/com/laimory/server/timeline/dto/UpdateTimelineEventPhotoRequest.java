package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** Event PATCH에서 append할 PHOTO Item 하나. itemType은 PHOTO로 고정되어 요청으로 받지 않는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateTimelineEventPhotoRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                example = "0190a1b2-0001-7000-8000-000000000001",
                description = "클라이언트 원본 사진 ID. 같은 DailyRecord 안에서 재시도 식별자로 사용한다 "
                        + "— canonical lowercase UUID(version 무관)만 허용하고 그 외는 400.")
        String rawId,
        @Schema(example = "2026-07-08T14:05:00", nullable = true,
                description = "사진 시각. 선택이며 보정 없이 저장한다.")
        LocalDateTime startAt,
        @Schema(example = "2026-07-08T14:05:00", nullable = true,
                description = "사진 종료 시각. 선택이며 보정 없이 저장한다.")
        LocalDateTime endAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "수동 PHOTO 입력. description은 받지 않고 photoUrl은 서버가 생성한다.")
        UpdateTimelineEventPhotoPayloadRequest payload
) {
}
