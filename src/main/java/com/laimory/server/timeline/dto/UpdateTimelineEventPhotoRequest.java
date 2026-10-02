package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** Event PATCH 또는 수동 Event 생성 POST에서 추가할 PHOTO Item 하나. itemType은 PHOTO로 고정된다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateTimelineEventPhotoRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                example = "0190a1b2-0001-7000-8000-000000000001",
                description = "클라이언트 원본 사진 ID. 대상 Event 안에서 같은 요청의 재시도 식별자로 사용한다 "
                        + "— canonical lowercase UUID(version 무관)만 허용하고 그 외는 400. 대상 Event에 이미 "
                        + "연결된 rawId는 오류 없이 건너뛰고(같은 요청의 재시도) 그 외는 새 Item이다.")
        String rawId,
        @Schema(example = "2026-07-08T14:05:00", nullable = true,
                description = "사진 시각. 선택이며 초 단위만 허용한다(소수 초는 400). 보정 없이 저장한다.")
        LocalDateTime startAt,
        @Schema(example = "2026-07-08T14:05:00", nullable = true,
                description = "사진 종료 시각. 선택이며 초 단위만 허용한다(소수 초는 400). 보정 없이 저장한다.")
        LocalDateTime endAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "수동 PHOTO 입력. description은 받지 않고 photoUrl은 서버가 생성한다.")
        UpdateTimelineEventPhotoPayloadRequest payload
) {
}
