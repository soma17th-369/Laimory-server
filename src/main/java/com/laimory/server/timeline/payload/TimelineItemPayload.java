package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.laimory.server.timeline.ItemType;

/**
 * 타입별 payload의 공통 인터페이스(sealed). DB에는 JSON으로 저장하되 Java에선 typed payload로 다룬다.
 *
 * itemType discriminator는 JSON 안에 단 한 번 등장하며 {@link JsonTypeInfo}가 직접 주입/소비한다.
 * payload 단독 역직렬화(DB JSON 컬럼)에서도 타입을 복원할 수 있어야 하므로 discriminator는 payload 안에 둔다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "itemType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PhotoPayload.class, name = "PHOTO"),
        @JsonSubTypes.Type(value = CalendarPayload.class, name = "CALENDAR"),
        @JsonSubTypes.Type(value = LocationPayload.class, name = "LOCATION"),
        @JsonSubTypes.Type(value = MovementPayload.class, name = "MOVEMENT")
})
public sealed interface TimelineItemPayload
        permits PhotoPayload, CalendarPayload, LocationPayload, MovementPayload {

    // 앱 switch용 파생값. @JsonTypeInfo가 itemType을 discriminator로 다루므로,
    // 이 메서드는 직렬화 대상에서 제외해 JSON에 itemType이 중복 등장하지 않게 한다.
    @JsonIgnore
    ItemType itemType();
}
