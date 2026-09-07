package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.EmotionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 저장 완료 하루 기록의 감정 수정 요청. 필수값은 Bean Validation, literal은 Jackson이 검증한다. */
public record UpdateDailyRecordEmotionRequest(
        @NotNull
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "HAPPY",
                description = "교체할 하루 감정(5단계). 필수 — 누락·null·미지원 값·숫자 등 비문자열은 400.")
        EmotionType emotionType
) {
}
