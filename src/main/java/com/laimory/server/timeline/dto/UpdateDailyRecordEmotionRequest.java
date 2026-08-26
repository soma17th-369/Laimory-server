package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.laimory.server.timeline.EmotionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;

/**
 * 저장 완료 하루 기록의 감정 수정 요청 — 교체할 하루 감정을 필수로 받는다.
 *
 * <p>{@code emotionType}은 필수 non-null이며 문자열 literal만 허용한다. 필드 누락({@code {}})과 명시적
 * {@code null}은 {@link IllegalArgumentException}으로 수렴해 400 {@code -400}이 되고, 미지원 literal·
 * 숫자 등 비문자열 token({@link StrictEmotionTypeDeserializer}가 Jackson의 ordinal coercion 차단)·
 * 깨진 JSON·zero-byte body도 같은 400 계약이다. body는 있는데 Content-Type이 없거나 JSON이 아니면
 * 415 {@code -415}다. wire는 save 요청과 같지만 use case가 달라 DTO를 공유하지 않는다.
 */
public record UpdateDailyRecordEmotionRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "HAPPY",
                description = "교체할 하루 감정(5단계). 필수 — 누락·null·미지원 값·숫자 등 비문자열은 400.")
        @JsonDeserialize(using = UpdateDailyRecordEmotionRequest.StrictEmotionTypeDeserializer.class)
        EmotionType emotionType
) {

    public UpdateDailyRecordEmotionRequest {
        if (emotionType == null) {
            throw new IllegalArgumentException("emotionType is required");
        }
    }

    /**
     * emotionType을 JSON 문자열로만 읽어 숫자 ordinal coercion을 막는다
     * ({@code StrictErrorCodeDeserializer}와 같은 방식). 명시적 null은 기본 null 처리로 compact
     * constructor의 400 경로에 합류하고, 미지원 literal은 위임한 표준 enum 변환이 400으로 거절한다.
     */
    static final class StrictEmotionTypeDeserializer extends JsonDeserializer<EmotionType> {

        @Override
        public EmotionType deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (EmotionType) context.handleUnexpectedToken(EmotionType.class, parser);
            }
            return context.readValue(parser, EmotionType.class);
        }
    }
}
