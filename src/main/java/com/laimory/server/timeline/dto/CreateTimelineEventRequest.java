package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.laimory.server.timeline.TimelineEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 타임라인 Event 수동 생성(POST) 요청 — {@code eventType·title·subtitle·startAt·endAt} 5개 키를
 * <b>모두 보내는</b> 계약(키 누락은 400). subtitle·endAt은 값이 nullable이고, eventType은 명시적
 * {@code null}·미지원 literal·숫자 등 비문자열 token(Jackson ordinal coercion 차단)이 모두 400이다.
 * title·startAt의 {@code null}은 서비스 검증에서 400이다.
 *
 * <p>{@code memo}만 optional 키다 — 누락과 null 모두 신규 Event의 메모 없음을 뜻해 PATCH와 달리
 * presence 구분이 없다. {@code photosToAdd}·{@code question}·{@code place}·{@code address}·item은
 * 이 요청의 schema에 없다 — 사진은 생성 응답의 {@code timelineEventId}로 기존 Event PATCH
 * {@code photosToAdd}를 호출해 추가한다.
 *
 * <p>Jackson 기본 역직렬화는 키 누락도 {@code null}로 만들어 "누락=400 / 명시적 null=비움"을 구분할 수
 * 없으므로, {@link KeyPresenceDeserializer}가 5개 키의 존재를 먼저 검증한다
 * ({@link UpdateTimelineEventRequest}와 같은 방식).
 */
@JsonDeserialize(using = CreateTimelineEventRequest.KeyPresenceDeserializer.class)
public record CreateTimelineEventRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "REST",
                description = "이벤트 분류. 키·값 모두 필수 — 누락·명시적 null·미지원 literal·숫자 등 "
                        + "비문자열은 400. 허용값은 응답의 eventType과 같다(UNKNOWN 포함).")
        TimelineEventType eventType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "카페에서 휴식",
                description = "이벤트 제목. 앞뒤 공백 제거 후 1~255자 필수 — null이거나 공백뿐이면 400.")
        String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "성수동",
                description = "이벤트 부제목. 키 자체는 필수(누락이면 400)이고 값은 nullable — "
                        + "null이거나 공백뿐이면 비움(null 저장). 그 외 앞뒤 공백 제거 후 최대 255자.")
        String subtitle,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07-08T14:00:00",
                description = "이벤트 시작 시각(타임존 없는 벽시계 LocalDateTime). 필수 — null이면 400. "
                        + "보낸 값 그대로 저장한다(AI 결과 저장의 +10분 충돌 보정 없음).")
        LocalDateTime startAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-07-08T15:00:00",
                description = "이벤트 종료 시각. 키 자체는 필수(누락이면 400)이고 값은 nullable — "
                        + "null이면 비움(단일 시점). 값이 있으면 startAt 이상이어야 한다(아니면 400).")
        LocalDateTime endAt,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true,
                description = "이벤트 메모. optional 키 — 누락과 null 모두 메모 없음이고, 공백뿐도 없음으로 "
                        + "정규화한다. 그 외 원문을 저장한다(최대 500자).")
        String memo
) {

    /**
     * 5개 키의 presence를 강제하는 역직렬화기. 키가 하나라도 없으면 {@code MismatchedInputException}을
     * 던지고, Spring MVC가 {@code HttpMessageNotReadableException}(→ 400 {@code -400})으로 매핑한다 —
     * 깨진 JSON과 같은 경로다. eventType은 값도 non-null 문자열 literal 계약이라 명시적 null과
     * 숫자 등 비문자열 token(Jackson의 enum ordinal coercion)을 여기서 거부한다.
     * 키가 다 있으면 값 변환은 context에 위임한다(포맷 오류도 동일하게 400).
     */
    static final class KeyPresenceDeserializer extends StdDeserializer<CreateTimelineEventRequest> {

        private static final java.util.List<String> REQUIRED_KEYS =
                java.util.List.of("eventType", "title", "subtitle", "startAt", "endAt");
        private static final String EVENT_TYPE_KEY = "eventType";
        private static final String MEMO_KEY = "memo";

        KeyPresenceDeserializer() {
            super(CreateTimelineEventRequest.class);
        }

        @Override
        public CreateTimelineEventRequest deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = context.readTree(parser);
            for (String key : REQUIRED_KEYS) {
                if (!node.has(key)) {
                    context.reportInputMismatch(CreateTimelineEventRequest.class,
                            "required key is missing: %s", key);
                }
            }
            JsonNode eventTypeValue = node.get(EVENT_TYPE_KEY);
            if (eventTypeValue.isNull()) {
                context.reportInputMismatch(CreateTimelineEventRequest.class,
                        "eventType must not be null");
            }
            // Jackson 기본 coercion은 숫자를 enum ordinal로 받아들인다 — 문자열 literal 계약이라 차단한다.
            if (!eventTypeValue.isTextual()) {
                context.reportInputMismatch(CreateTimelineEventRequest.class,
                        "eventType must be a string literal");
            }
            return new CreateTimelineEventRequest(
                    context.readTreeAsValue(eventTypeValue, TimelineEventType.class),
                    readNullable(context, node, "title", String.class),
                    readNullable(context, node, "subtitle", String.class),
                    readNullable(context, node, "startAt", LocalDateTime.class),
                    readNullable(context, node, "endAt", LocalDateTime.class),
                    readNullable(context, node, MEMO_KEY, String.class));
        }

        private <T> T readNullable(DeserializationContext context, JsonNode node, String key, Class<T> type)
                throws IOException {
            JsonNode value = node.get(key);
            return value == null || value.isNull() ? null : context.readTreeAsValue(value, type);
        }
    }
}
