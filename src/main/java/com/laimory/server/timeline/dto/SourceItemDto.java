package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.HealthPayload;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.NotificationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.TimelineItemPayload;
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
        ItemType itemType,
        String rawId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "itemType", visible = true)
        @JsonSubTypes({
                @JsonSubTypes.Type(value = PhotoPayload.class, name = "PHOTO"),
                @JsonSubTypes.Type(value = CalendarPayload.class, name = "CALENDAR"),
                @JsonSubTypes.Type(value = LocationPayload.class, name = "LOCATION"),
                @JsonSubTypes.Type(value = MovementPayload.class, name = "MOVEMENT"),
                @JsonSubTypes.Type(value = HealthPayload.class, name = "HEALTH"),
                @JsonSubTypes.Type(value = NotificationPayload.class, name = "NOTIFICATION")
        })
        TimelineItemPayload payload
) {
}
