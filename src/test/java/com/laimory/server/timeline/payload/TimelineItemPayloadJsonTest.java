package com.laimory.server.timeline.payload;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.SourceItemDto;
import org.junit.jupiter.api.Test;

/**
 * STAGE 1 와이어 계약(최대 리스크)을 DB 없이 검증한다. itemType은 payload 밖으로 이동했다.
 * - payload 직렬화엔 itemType이 전혀 없다(타입 정보 없는 raw JSON).
 * - SourceItemDto는 itemType을 payload 형제 필드(EXTERNAL_PROPERTY)로 받아 round-trip된다.
 * - 직렬화 시 itemType은 payload 밖 형제로 나간다(payload 안이 아님).
 * - {@link ItemTypes#typeOf}가 구체 타입을 올바른 ItemType으로 매핑한다.
 *
 * SourceItemDto.startAt(LocalDateTime) 때문에 JSR-310 모듈 등록이 필요하다(findAndRegisterModules).
 */
class TimelineItemPayloadJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // --- payload 자체엔 타입 정보가 없다 ---

    @Test
    void photoPayload_hasNoTypeInfo() throws Exception {
        String json = objectMapper.writeValueAsString(new PhotoPayload("u", 1.0, 2.0));
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
                {"itemId":1,"itemType":"MOVEMENT","startAt":"2026-05-08T08:30:00","endAt":null,"summary":"s",
                 "payload":{"fromPlace":"강남역","toPlace":"성수역","transportMode":"SUBWAY","lineName":"7호선"}}
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
                {"itemId":0,"itemType":"PHOTO","startAt":"2026-06-17T09:00:00","endAt":null,"summary":"s",
                 "payload":{"photoUri":"u","latitude":1.0,"longitude":2.0}}
                """;

        SourceItemDto dto = objectMapper.readValue(json, SourceItemDto.class);

        assertThat(dto.itemType()).isEqualTo(ItemType.PHOTO);
        assertThat(dto.payload()).isInstanceOf(PhotoPayload.class)
                .isEqualTo(new PhotoPayload("u", 1.0, 2.0));
    }

    @Test
    void sourceItemDto_serializes_itemTypeAsSiblingOfPayload() throws Exception {
        SourceItemDto dto = new SourceItemDto(
                1, ItemType.MOVEMENT,
                java.time.LocalDateTime.of(2026, 5, 8, 8, 30), null, "s",
                new MovementPayload("강남역", "성수역", "SUBWAY", "7호선"));

        com.fasterxml.jackson.databind.JsonNode tree = objectMapper.valueToTree(dto);

        // itemType은 최상위(payload 형제)에 있고, payload 안엔 없다.
        assertThat(tree.has("itemType")).isTrue();
        assertThat(tree.get("itemType").asText()).isEqualTo("MOVEMENT");
        assertThat(tree.get("payload").has("itemType")).isFalse();
    }

    // --- ItemTypes.typeOf ---

    @Test
    void typeOf_mapsEveryConcretePayload() {
        assertThat(ItemTypes.typeOf(new PhotoPayload("u", 1.0, 2.0))).isEqualTo(ItemType.PHOTO);
        assertThat(ItemTypes.typeOf(new CalendarPayload("t", "c", "l"))).isEqualTo(ItemType.CALENDAR);
        assertThat(ItemTypes.typeOf(new LocationPayload("p", "a", 1.0, 2.0))).isEqualTo(ItemType.LOCATION);
        assertThat(ItemTypes.typeOf(new MovementPayload("f", "t", "m", "l"))).isEqualTo(ItemType.MOVEMENT);
    }
}
