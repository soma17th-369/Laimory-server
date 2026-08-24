package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.EmotionType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 저장 완료 하루 기록의 감정 수정 요청 — 교체할 하루 감정을 필수로 받는다.
 *
 * <p>{@code emotionType}은 필수 non-null이다. 필드 누락({@code {}})과 명시적 {@code null}은
 * {@link IllegalArgumentException}으로 수렴해 400 {@code -400}이 되고, 미지원 literal·깨진 JSON·
 * zero-byte body도 같은 400 계약이다. body는 있는데 Content-Type이 없거나 JSON이 아니면
 * 415 {@code -415}다. wire는 save 요청과 같지만 use case가 달라 DTO를 공유하지 않는다.
 */
public record UpdateDailyRecordEmotionRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "HAPPY",
                description = "교체할 하루 감정(5단계). 필수 — 누락·null·미지원 값은 400.")
        EmotionType emotionType
) {

    public UpdateDailyRecordEmotionRequest {
        if (emotionType == null) {
            throw new IllegalArgumentException("emotionType is required");
        }
    }
}
