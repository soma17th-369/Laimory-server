package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.laimory.server.timeline.TimelineEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 타임라인 Event 수정(PATCH) 요청 — title·subtitle·startAt·endAt 4개 필드를 <b>모두 보내는</b> 절대값 대입 계약.
 *
 * <p>부분 전송이 아니다: 4개 키가 항상 요청에 있어야 하며(키 누락은 400), 4개 필드가 항상 이 요청의 값으로
 * 교체된다. title·startAt의 {@code null}은 400이고,
 * subtitle·endAt은 <b>명시적 {@code null}만</b> "비움"이다 — 유지하고 싶은 값은 현재 값을 그대로 담아 보낸다.
 *
 * <p>{@code eventType}, {@code memo}, {@code photosToAdd}는 optional 키다. eventType 키 누락은 현재 값 유지,
 * 키가 있으면 non-null 허용 literal만 받는다(명시적 {@code null}·미지원 literal은 400). record 필드의
 * {@code eventType null}은 "키 누락"만 의미한다(명시적 null은 역직렬화에서 거부되므로 도달 불가).
 * memo는 누락과 명시적 null을 {@code memoPresent}로 구분하며, photosToAdd 누락은 빈 목록(no-op),
 * 명시적 null은 400이다. photosToAdd는 PHOTO Item 추가만 표현하며 기존 Item을 교체하지 않는다.
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
        LocalDateTime endAt,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "MEAL",
                description = "이벤트 분류. 4개 키와 달리 <b>optional 키</b>다 — 키 누락이면 현재 값을 유지한다. "
                        + "키가 있으면 허용 literal만 받는다: 명시적 null·미지원 값은 400. "
                        + "허용값은 응답의 eventType과 같다(UNKNOWN 포함).")
        TimelineEventType eventType,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true,
                description = "이벤트 메모. 키 누락은 유지, null 또는 공백뿐이면 제거, 그 외 원문을 저장한다(최대 500자).")
        String memo,
        @JsonIgnore
        @Schema(hidden = true)
        boolean memoPresent,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                description = "추가할 PHOTO Item 목록. 누락 또는 빈 배열은 변경 없음이며, 명시적 null은 400.")
        List<UpdateTimelineEventPhotoRequest> photosToAdd
) {

    /**
     * 4개 키의 presence를 강제하는 역직렬화기. 키가 하나라도 없으면 {@code MismatchedInputException}을
     * 던지고, Spring MVC가 {@code HttpMessageNotReadableException}(→ 400 {@code -400})으로 매핑한다 —
     * 깨진 JSON과 같은 경로다. 키가 다 있으면 값 변환은 context에 위임한다(포맷 오류도 동일하게 400).
     * eventType은 optional 키라 presence를 강제하지 않되, 키가 있으면 명시적 null을 여기서 거부한다.
     */
    static final class KeyPresenceDeserializer extends StdDeserializer<UpdateTimelineEventRequest> {

        private static final List<String> REQUIRED_KEYS = List.of("title", "subtitle", "startAt", "endAt");
        private static final String EVENT_TYPE_KEY = "eventType";
        private static final String MEMO_KEY = "memo";
        private static final String PHOTOS_TO_ADD_KEY = "photosToAdd";

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
                    readNullable(context, node, "endAt", LocalDateTime.class),
                    readOptionalEventType(context, node),
                    readNullable(context, node, MEMO_KEY, String.class),
                    node.has(MEMO_KEY),
                    readPhotosToAdd(context, node));
        }

        private <T> T readNullable(DeserializationContext context, JsonNode node, String key, Class<T> type)
                throws IOException {
            JsonNode value = node.get(key);
            return value == null || value.isNull() ? null : context.readTreeAsValue(value, type);
        }

        /** 키 누락은 null(=현재 값 유지), 명시적 null은 400. 미지원 literal은 enum 변환 실패로 같은 400 경로다. */
        private TimelineEventType readOptionalEventType(DeserializationContext context, JsonNode node)
                throws IOException {
            if (!node.has(EVENT_TYPE_KEY)) {
                return null;
            }
            JsonNode value = node.get(EVENT_TYPE_KEY);
            if (value.isNull()) {
                context.reportInputMismatch(UpdateTimelineEventRequest.class,
                        "eventType must not be null when present");
            }
            return context.readTreeAsValue(value, TimelineEventType.class);
        }

        /** 키 누락은 empty(no-op), 명시적 null·배열 아닌 값은 400. null element는 서비스 검증까지 보존한다. */
        private List<UpdateTimelineEventPhotoRequest> readPhotosToAdd(
                DeserializationContext context, JsonNode node) throws IOException {
            if (!node.has(PHOTOS_TO_ADD_KEY)) {
                return List.of();
            }
            JsonNode value = node.get(PHOTOS_TO_ADD_KEY);
            if (value.isNull() || !value.isArray()) {
                context.reportInputMismatch(UpdateTimelineEventRequest.class,
                        "photosToAdd must be an array when present");
            }
            List<UpdateTimelineEventPhotoRequest> photos = new ArrayList<>(value.size());
            for (JsonNode photo : value) {
                photos.add(photo.isNull()
                        ? null
                        : context.readTreeAsValue(photo, UpdateTimelineEventPhotoRequest.class));
            }
            return photos;
        }
    }
}
