package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.HealthPayload;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.NotificationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.StayPayload;
import com.laimory.server.timeline.payload.TimelineItemPayload;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 소스 아이템(서버가 AI 요청 전에 만든 임시 입력 데이터). DB 엔티티가 아니다.
 *
 * <p>{@code itemType}은 payload 밖 형제 필드(external property)로 받는 타입 디스크리미네이터다. payload JSON엔 타입 정보가 없다.
 * {@code visible = true}라 itemType이 이 레코드 컴포넌트에도 바인딩된다.
 *
 * <p>{@code rawId}는 클라가 부여하는 기기 원본 데이터 식별자(UUIDv7)다. payload가 아닌 **envelope 필드**로
 * 받아 컬럼으로 저장한다 — 서버는 해석·정규화 없이 echo만 한다(필수, blank·길이만 검증).
 */
public record SourceItemDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "PHOTO",
                description = "payload 변형을 결정하는 디스크리미네이터(payload 밖 형제 필드).")
        ItemType itemType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 36,
                example = "0190a1b2-0001-7000-8000-000000000001",
                description = "클라 기기 원본 데이터 식별자(UUIDv7 관례). 필수·최대 36자 — 형식은 검증하지 않고 그대로 저장/echo.")
        String rawId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-07-08T09:05:00",
                description = "아이템 시작(벽시계 LocalDateTime, offset 없음). 필수 — 누락 시 400/-400. "
                        + "AI 시간창·durationText 계산과 지오코딩 시간순 품질 판정에 쓰인다.")
        LocalDateTime startAt,
        @Schema(nullable = true, example = "2026-07-08T09:05:00",
                description = "아이템 종료(벽시계 LocalDateTime, offset 없음). 선택(단일 시점이면 null).")
        LocalDateTime endAt,
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "itemType", visible = true)
        @JsonSubTypes({
                @JsonSubTypes.Type(value = PhotoPayload.class, name = "PHOTO"),
                @JsonSubTypes.Type(value = CalendarPayload.class, name = "CALENDAR"),
                @JsonSubTypes.Type(value = StayPayload.class, name = "STAY"),
                @JsonSubTypes.Type(value = MovementPayload.class, name = "MOVEMENT"),
                @JsonSubTypes.Type(value = HealthPayload.class, name = "HEALTH"),
                @JsonSubTypes.Type(value = NotificationPayload.class, name = "NOTIFICATION")
        })
        TimelineItemPayload payload
) {
}
