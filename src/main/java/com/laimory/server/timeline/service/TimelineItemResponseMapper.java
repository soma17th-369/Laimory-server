package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.PhotoPayloadResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link TimelineItem}(엔티티) → {@link TimelineItemResponse}(응답) 매퍼.
 *
 * <p>PHOTO 아이템만 변환한다: DB payload엔 {@code filename}만 있으므로, 읽을 때 사용자 id로 full key를 파생해
 * 무서명 서빙 URL({@code photoUrl})을 구성하고 {@link PhotoPayloadResponse}로 직렬화해 넣는다(DB엔 URL 미저장).
 * 그 외 타입(CALENDAR/LOCATION/MOVEMENT)은 payload JsonNode를 그대로 통과시킨다 — 와이어 형태가 바뀌지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TimelineItemResponseMapper {

    private final ObjectMapper objectMapper;
    private final PhotoUrlService photoUrlService;

    /** userId는 PHOTO의 full key 파생(→photoUrl)에 쓴다. DailyRecord의 user_id를 넘긴다. */
    public TimelineItemResponse toResponse(TimelineItem item, long userId) {
        JsonNode payload = item.getPayload();
        if (item.getItemType() == ItemType.PHOTO) {
            payload = toPhotoResponsePayload(payload, userId);
        }
        return new TimelineItemResponse(
                item.getTimelineItemId(),
                item.getItemType(),
                item.getStartAt(),
                item.getEndAt(),
                payload);
    }

    private JsonNode toPhotoResponsePayload(JsonNode payload, long userId) {
        PhotoPayload photo;
        try {
            photo = objectMapper.treeToValue(payload, PhotoPayload.class);
        } catch (JsonProcessingException e) {
            // 저장 시 filename 형식을 보장하므로 도달하지 않는다(불변식 위반 → 500).
            throw new IllegalStateException("invalid PHOTO payload in DB: " + payload, e);
        }
        String photoUrl = photoUrlService.buildUrl(photo.filename(), userId);
        return objectMapper.valueToTree(
                new PhotoPayloadResponse(photoUrl, photo.latitude(), photo.longitude()));
    }
}
