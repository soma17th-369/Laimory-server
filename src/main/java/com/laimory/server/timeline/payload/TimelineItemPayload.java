package com.laimory.server.timeline.payload;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 타입별 payload의 공통 sealed 인터페이스. DB에는 타입 정보 없는 JSON으로 저장하고,
 * 타입은 timeline_items.item_type 컬럼이 권위다(payload 밖). item_type 값은 클라이언트가 보낸 itemType 디스크리미네이터에서 온다.
 *
 * <p>Swagger에는 6개 구체 변형을 {@code oneOf}로 노출한다(요청·응답 공용 단일 스키마). discriminator는 payload
 * 밖 형제 필드({@code itemType})라 OpenAPI inline discriminator를 쓰지 않고 oneOf만 둔다.
 */
@Schema(description = "itemType에 대응하는 타입별 payload(6종 중 하나)",
        oneOf = {PhotoPayload.class, CalendarPayload.class, StayPayload.class,
                MovementPayload.class, HealthPayload.class, NotificationPayload.class})
public sealed interface TimelineItemPayload
        permits PhotoPayload, CalendarPayload, StayPayload, MovementPayload, HealthPayload, NotificationPayload {
}
