package com.laimory.server.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 타임라인 생성 가능 확인 응답. 클라는 이 값으로 생성 버튼 활성 여부를 정한다. */
public record CheckDraftResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "true",
                description = "받은 기록만으로 타임라인을 만들 수 있으면 true.")
        boolean creatable
) {
}
