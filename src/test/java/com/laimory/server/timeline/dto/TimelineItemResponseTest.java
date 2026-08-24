package com.laimory.server.timeline.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 엔티티→응답 정적 팩토리 검증. payload는 저장본 그대로 통과한다 —
 * PHOTO도 특수 변환 없이 photoUrl(저장 시 주입)·filename이 그대로 내려간다.
 */
class TimelineItemResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void from_copiesEnvelopeFields_andPassesPayloadThroughUnchanged() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        PhotoPayload payload = new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://local/42",
                1.0, 2.0, "사진 설명", null, null, "https://cdn.example/hash/photos/0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg");
        TimelineItem item = TimelineItem.of(ItemType.PHOTO, "raw-21", t, null,
                objectMapper.valueToTree(payload));
        ReflectionTestUtils.setField(item, "timelineItemId", 21L);

        TimelineItemResponse response = TimelineItemResponse.from(item);

        assertThat(response.timelineItemId()).isEqualTo(21L);
        assertThat(response.itemType()).isEqualTo(ItemType.PHOTO);
        assertThat(response.rawId()).isEqualTo("raw-21");
        assertThat(response.startAt()).isEqualTo(t);
        assertThat(response.endAt()).isNull();
        // 저장본 pass-through — photoUrl(서버 주입)과 filename 둘 다 그대로.
        assertThat(response.payload().get("photoUrl").asText())
                .isEqualTo("https://cdn.example/hash/photos/0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg");
        assertThat(response.payload().get("filename").asText()).isEqualTo("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg");
        assertThat(response.payload().get("clientPhotoUri").asText()).isEqualTo("content://local/42");
        assertThat(response.payload().has("itemType")).isFalse();
    }
}
