package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.PhotoUrlService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 엔티티→응답 매퍼 단위 테스트. PHOTO는 filename→무서명 photoUrl로 구성하고, 그 외 타입은 payload를 그대로 통과시킨다.
 * 실제 ObjectMapper + PhotoUrlService(도메인만 주입, 네트워크 0)로 검증한다.
 */
class TimelineItemResponseMapperTest {

    private static final String CDN = "cdn.example.com";
    private static final long USER_ID = 7L;
    private static final String FILENAME = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PhotoUrlService photoUrlService = new PhotoUrlService(CDN);
    private final TimelineItemResponseMapper mapper =
            new TimelineItemResponseMapper(objectMapper, photoUrlService);

    private TimelineItem item(ItemType type, Object payload) {
        LocalDateTime t = LocalDateTime.of(2026, 6, 17, 9, 0);
        TimelineItem item = TimelineItem.of(11L, type, t, null, objectMapper.valueToTree(payload));
        ReflectionTestUtils.setField(item, "timelineItemId", 21L);
        return item;
    }

    @Test
    void photo_buildsUnsignedPhotoUrlFromFilename_andEchoesClientPhotoUri() {
        TimelineItem item = item(ItemType.PHOTO, new PhotoPayload(FILENAME, "content://local/42", 1.0, 2.0, "사진 설명"));

        TimelineItemResponse response = mapper.toResponse(item, USER_ID);

        assertThat(response.itemType()).isEqualTo(ItemType.PHOTO);
        // photoUrl = https://{cdn}/{sha256hex(userId)}/photos/{filename} (무서명 stable URL)
        String expected = "https://" + CDN + "/" + PhotoObjectKeys.fullKey(FILENAME, USER_ID);
        assertThat(response.payload().get("photoUrl").asText()).isEqualTo(expected);
        // 기기 로컬 URI는 그대로 echo(1차 로컬 캐싱용). description도 동일하게 echo.
        assertThat(response.payload().get("clientPhotoUri").asText()).isEqualTo("content://local/42");
        assertThat(response.payload().get("latitude").asDouble()).isEqualTo(1.0);
        assertThat(response.payload().get("longitude").asDouble()).isEqualTo(2.0);
        assertThat(response.payload().get("description").asText()).isEqualTo("사진 설명");
        // filename은 응답에서 사라지고 photoUrl로 대체된다.
        assertThat(response.payload().has("filename")).isFalse();
        assertThat(response.payload().has("itemType")).isFalse();
    }

    @Test
    void nonPhoto_passesPayloadThroughUnchanged() {
        TimelineItem item = item(ItemType.LOCATION, new LocationPayload("카페", "강남", 3.0, 4.0));

        TimelineItemResponse response = mapper.toResponse(item, USER_ID);

        assertThat(response.itemType()).isEqualTo(ItemType.LOCATION);
        assertThat(response.payload().get("placeName").asText()).isEqualTo("카페");
        assertThat(response.payload().get("areaName").asText()).isEqualTo("강남");
        // photoUrl 같은 변환은 PHOTO 외 타입엔 없다.
        assertThat(response.payload().has("photoUrl")).isFalse();
    }

    @Test
    void copiesIdentityFields() {
        TimelineItem item = item(ItemType.PHOTO, new PhotoPayload(FILENAME, "content://x", null, null, null));

        TimelineItemResponse response = mapper.toResponse(item, USER_ID);

        assertThat(response.timelineItemId()).isEqualTo(21L);
        assertThat(response.startAt()).isEqualTo(LocalDateTime.of(2026, 6, 17, 9, 0));
        assertThat(response.endAt()).isNull();
        // NON_NULL(PhotoPayloadResponse): null 필드는 응답 payload에 키 자체가 없다.
        assertThat(response.payload().has("latitude")).isFalse();
        assertThat(response.payload().has("longitude")).isFalse();
        assertThat(response.payload().has("description")).isFalse();
    }
}
