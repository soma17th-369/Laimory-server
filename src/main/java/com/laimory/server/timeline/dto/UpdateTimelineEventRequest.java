package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 타임라인 Event 수정(PATCH) 요청 — title·subtitle·startAt·endAt 4개 필드를 <b>모두 보내는</b> 절대값 대입 계약.
 *
 * <p>부분 전송이 아니다: 4개 키가 항상 요청에 있어야 하며(키 누락은 400), 4개 필드가 항상 이 요청의 값으로
 * 교체된다(memo·하위 items는 이 요청으로 바뀌지 않는다). title·startAt의 {@code null}은 400이고,
 * subtitle·endAt은 <b>명시적 {@code null}만</b> "비움"이다 — 유지하고 싶은 값은 현재 값을 그대로 담아 보낸다.
 *
 * <p>Jackson 기본 역직렬화는 키 누락도 {@code null}로 만들어 "누락=400 / 명시적 null=비움"을 구분할 수
 * 없으므로, {@link KeyPresenceDeserializer}가 4개 키의 존재를 먼저 검증한다.
 *
 * <p>시간은 보낸 값 그대로 저장된다 — draft 생성(AI finalize)의 +10분 충돌 보정이나 하위 Item 시간 변경은 없다.
 */
@JsonDeserialize(using = UpdateTimelineEventRequest.KeyPresenceDeserializer.class)
public record UpdateTimelineEventRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "카페에서 휴식",
                description = "이벤트 제목. 앞뒤 공백 제거 후 1~255자 필수 — null이거나 공백뿐이면 400.")
        String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "성수동 카페거리",
                description = "이벤트 부제목. 키 자체는 필수(누락이면 400)이고 값은 nullable — "
                        + "null이거나 공백뿐이면 비움(null 저장). 그 외 앞뒤 공백 제거 후 최대 255자.")
        String subtitle,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07-08T14:00:00",
                description = "이벤트 시작 시각(타임존 없는 벽시계 LocalDateTime). 필수 — null이면 400. "
                        + "보낸 값 그대로 저장한다(충돌 보정 없음).")
        LocalDateTime startAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-07-08T15:30:00",
                description = "이벤트 종료 시각. 키 자체는 필수(누락이면 400)이고 값은 nullable — "
                        + "null이면 비움(단일 시점). 값이 있으면 startAt 이상이어야 한다(아니면 400).")
        LocalDateTime endAt
) {

    /**
     * 4개 키의 presence를 강제하는 역직렬화기. 키가 하나라도 없으면 {@code MismatchedInputException}을
     * 던지고, Spring MVC가 {@code HttpMessageNotReadableException}(→ 400 {@code ERROR_0400})으로 매핑한다 —
     * 깨진 JSON과 같은 경로다. 키가 다 있으면 값 변환은 context에 위임한다(포맷 오류도 동일하게 400).
     */
    static final class KeyPresenceDeserializer extends StdDeserializer<UpdateTimelineEventRequest> {

        private static final List<String> REQUIRED_KEYS = List.of("title", "subtitle", "startAt", "endAt");

        KeyPresenceDeserializer() {
            super(UpdateTimelineEventRequest.class);
        }

        @Override
        public UpdateTimelineEventRequest deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = context.readTree(parser);
            for (String key : REQUIRED_KEYS) {
                if (!node.has(key)) {
                    context.reportInputMismatch(UpdateTimelineEventRequest.class,
                            "required key is missing: %s", key);
                }
            }
            return new UpdateTimelineEventRequest(
                    readNullable(context, node, "title", String.class),
                    readNullable(context, node, "subtitle", String.class),
                    readNullable(context, node, "startAt", LocalDateTime.class),
                    readNullable(context, node, "endAt", LocalDateTime.class));
        }

        private <T> T readNullable(DeserializationContext context, JsonNode node, String key, Class<T> type)
                throws IOException {
            JsonNode value = node.get(key);
            return value == null || value.isNull() ? null : context.readTreeAsValue(value, type);
        }
    }
}
