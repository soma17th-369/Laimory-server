package com.laimory.server.timeline.dto;

import com.laimory.server.timeline.payload.TimelineItemPayload;
import java.time.LocalDateTime;

/**
 * 소스 아이템(서버가 AI 요청 전에 만든 임시 입력 데이터). DB 엔티티가 아니다.
 *
 * <p>{@code itemId}는 클라이언트가 부여한 요청 범위 인덱스(request item id)일 뿐 DB의 timeline_items.id가 아니다.
 * {@code summary}는 AI 입력 컨텍스트로만 쓰이며 DB에 저장하지 않는다.
 * 아이템 타입은 payload의 discriminator가 단일 권위이므로 별도 itemType 필드를 두지 않는다.
 */
public record SourceItemDto(
        Integer itemId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String summary,
        TimelineItemPayload payload
) {
}
