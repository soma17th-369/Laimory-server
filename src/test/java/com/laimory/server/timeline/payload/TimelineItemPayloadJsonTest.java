package com.laimory.server.timeline.payload;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.CreateDraftTaskRequest;
import com.laimory.server.timeline.dto.SourceItemDto;
import org.junit.jupiter.api.Test;

/**
 * STAGE 1 와이어 계약(최대 리스크)을 DB 없이 검증한다. itemType은 payload 밖으로 이동했다.
 * - payload 직렬화엔 itemType이 전혀 없다(타입 정보 없는 raw JSON).
 * - SourceItemDto는 itemType을 payload 형제 필드(EXTERNAL_PROPERTY)로 받아 round-trip된다.
 * - 직렬화 시 itemType은 payload 밖 형제로 나간다(payload 안이 아님).
 *
 * SourceItemDto.startAt(LocalDateTime) 때문에 JSR-310 모듈 등록이 필요하다(findAndRegisterModules).
 */
class TimelineItemPayloadJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // --- payload 자체엔 타입 정보가 없다 ---

    @Test
    void photoPayload_hasNoTypeInfo() throws Exception {
        String json = objectMapper.writeValueAsString(new PhotoPayload("u", "content://x", 1.0, 2.0));
        assertThat(json).doesNotContain("itemType");
    }

    @Test
    void calendarPayload_hasNoTypeInfo() throws Exception {
        String json = objectMapper.writeValueAsString(new CalendarPayload("주간 회의", "회사", "회의실 A"));
        assertThat(json).doesNotContain("itemType");
    }

    @Test
    void locationPayload_hasNoTypeInfo() throws Exception {
        String json = objectMapper.writeValueAsString(new LocationPayload("작은 카페", "성수동", 37.5445, 127.0557));
        assertThat(json).doesNotContain("itemType");
    }

    @Test
    void movementPayload_hasNoTypeInfo() throws Exception {
        String json = objectMapper.writeValueAsString(new MovementPayload("강남역", "성수역", "SUBWAY", "7호선"));
        assertThat(json).doesNotContain("itemType");
    }

    @Test
    void valueToTree_hasNoTypeInfo() {
        // 쓰기 경로(DailyTimelineService)가 쓰는 valueToTree에도 itemType이 없어야 한다.
        assertThat(objectMapper.valueToTree(new MovementPayload("강남역", "성수역", "SUBWAY", "7호선")).toString())
                .doesNotContain("itemType");
    }

    // --- SourceItemDto external-property round-trip (itemType은 payload 형제) ---

    @Test
    void sourceItemDto_externalProperty_roundTrip_movement() throws Exception {
        String json = """
                {"itemType":"MOVEMENT","startAt":"2026-05-08T08:30:00","endAt":null,                 "payload":{"fromPlace":"강남역","toPlace":"성수역","transportMode":"SUBWAY","lineName":"7호선"}}
                """;

        SourceItemDto dto = objectMapper.readValue(json, SourceItemDto.class);

        assertThat(dto.itemType()).isEqualTo(ItemType.MOVEMENT);
        assertThat(dto.payload()).isInstanceOf(MovementPayload.class)
                .isEqualTo(new MovementPayload("강남역", "성수역", "SUBWAY", "7호선"));
        assertThat(dto.startAt()).isEqualTo(java.time.LocalDateTime.of(2026, 5, 8, 8, 30));
    }

    @Test
    void sourceItemDto_externalProperty_roundTrip_photo() throws Exception {
        String json = """
                {"itemType":"PHOTO","startAt":"2026-06-17T09:00:00","endAt":null,                 "payload":{"filename":"u","clientPhotoUri":"c","latitude":1.0,"longitude":2.0}}
                """;

        SourceItemDto dto = objectMapper.readValue(json, SourceItemDto.class);

        assertThat(dto.itemType()).isEqualTo(ItemType.PHOTO);
        assertThat(dto.payload()).isInstanceOf(PhotoPayload.class)
                .isEqualTo(new PhotoPayload("u", "c", 1.0, 2.0));
    }

    @Test
    void sourceItemDto_externalProperty_itemTypeAfterPayload() throws Exception {
        // 필드 순서 회귀: payload가 먼저, itemType(외부 디스크리미네이터)이 마지막.
        // Jackson은 itemType을 볼 때까지 payload를 버퍼링해야 한다.
        String json = """
                {"startAt":null,"endAt":null,                 "payload":{"filename":"u","clientPhotoUri":"c","latitude":1.0,"longitude":2.0},"itemType":"PHOTO"}
                """;

        SourceItemDto dto = objectMapper.readValue(json, SourceItemDto.class);

        assertThat(dto.payload()).isInstanceOf(PhotoPayload.class)
                .isEqualTo(new PhotoPayload("u", "c", 1.0, 2.0));
        assertThat(dto.itemType()).isEqualTo(ItemType.PHOTO);
    }

    @Test
    void createDraftTaskRequest_nested_itemTypeAfterPayload() throws Exception {
        // 컨트롤러가 역직렬화하는 실제 요청 바디 형태: sourceItems 배열의 각 아이템에서
        // payload가 itemType보다 먼저 온다. 중첩 컨텍스트에서도 외부 프로퍼티 버퍼링이 동작해야 한다.
        String json = """
                {"recordAt":"2026-05-08T12:30:00","recordTimeZone":"Asia/Seoul","sourceItems":[
                  {"startAt":null,"endAt":null,                   "payload":{"fromPlace":"강남역","toPlace":"성수역","transportMode":"SUBWAY","lineName":"7호선"},
                   "itemType":"MOVEMENT"}
                ]}
                """;

        CreateDraftTaskRequest req = objectMapper.readValue(json, CreateDraftTaskRequest.class);

        assertThat(req.sourceItems().get(0).payload()).isInstanceOf(MovementPayload.class)
                .isEqualTo(new MovementPayload("강남역", "성수역", "SUBWAY", "7호선"));
        assertThat(req.sourceItems().get(0).itemType()).isEqualTo(ItemType.MOVEMENT);
    }

    @Test
    void sourceItemDto_serializes_itemTypeAsSiblingOfPayload() throws Exception {
        SourceItemDto dto = new SourceItemDto(
                ItemType.MOVEMENT,
                java.time.LocalDateTime.of(2026, 5, 8, 8, 30), null,
                new MovementPayload("강남역", "성수역", "SUBWAY", "7호선"));

        com.fasterxml.jackson.databind.JsonNode tree = objectMapper.valueToTree(dto);

        // itemType은 최상위(payload 형제)에 있고, payload 안엔 없다.
        assertThat(tree.has("itemType")).isTrue();
        assertThat(tree.get("itemType").asText()).isEqualTo("MOVEMENT");
        assertThat(tree.get("payload").has("itemType")).isFalse();
    }
}
