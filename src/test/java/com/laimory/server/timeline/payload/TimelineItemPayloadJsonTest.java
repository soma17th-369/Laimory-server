package com.laimory.server.timeline.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payload 폴리모픽 매핑(최대 리스크)을 DB 없이 검증한다.
 * - 직렬화 시 itemType discriminator가 JSON에 정확히 1회 등장하는지(중복 방지)
 * - 역직렬화 시 sealed 서브타입으로 정확히 복원되고 필드가 보존되는지
 */
class TimelineItemPayloadJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void photoPayload_roundTrip() throws Exception {
        PhotoPayload original = new PhotoPayload("content://media/external/images/media/12345", 37.5445, 127.0557);

        String json = objectMapper.writeValueAsString(original);

        assertThat(json).contains("\"itemType\":\"PHOTO\"");
        assertThat(countOccurrences(json, "\"itemType\"")).isEqualTo(1);

        TimelineItemPayload restored = objectMapper.readValue(json, TimelineItemPayload.class);
        assertThat(restored).isInstanceOf(PhotoPayload.class).isEqualTo(original);
        assertThat(restored.itemType()).isEqualTo(ItemType.PHOTO);
    }

    @Test
    void calendarPayload_roundTrip() throws Exception {
        CalendarPayload original = new CalendarPayload("주간 회의", "회사", "회의실 A");

        String json = objectMapper.writeValueAsString(original);

        assertThat(json).contains("\"itemType\":\"CALENDAR\"");
        assertThat(countOccurrences(json, "\"itemType\"")).isEqualTo(1);

        TimelineItemPayload restored = objectMapper.readValue(json, TimelineItemPayload.class);
        assertThat(restored).isInstanceOf(CalendarPayload.class).isEqualTo(original);
        assertThat(restored.itemType()).isEqualTo(ItemType.CALENDAR);
    }

    @Test
    void locationPayload_roundTrip() throws Exception {
        LocationPayload original = new LocationPayload("작은 카페", "성수동", 37.5445, 127.0557);

        String json = objectMapper.writeValueAsString(original);

        assertThat(json).contains("\"itemType\":\"LOCATION\"");
        assertThat(countOccurrences(json, "\"itemType\"")).isEqualTo(1);

        TimelineItemPayload restored = objectMapper.readValue(json, TimelineItemPayload.class);
        assertThat(restored).isInstanceOf(LocationPayload.class).isEqualTo(original);
        assertThat(restored.itemType()).isEqualTo(ItemType.LOCATION);
    }

    @Test
    void movementPayload_roundTrip() throws Exception {
        MovementPayload original = new MovementPayload("강남역", "성수역", "SUBWAY", "7호선");

        String json = objectMapper.writeValueAsString(original);

        assertThat(json).contains("\"itemType\":\"MOVEMENT\"");
        assertThat(countOccurrences(json, "\"itemType\"")).isEqualTo(1);

        TimelineItemPayload restored = objectMapper.readValue(json, TimelineItemPayload.class);
        assertThat(restored).isInstanceOf(MovementPayload.class).isEqualTo(original);
        assertThat(restored.itemType()).isEqualTo(ItemType.MOVEMENT);
    }

    @Test
    void deserializes_designNote_contract_json() throws Exception {
        // 설계노트에 명시된 JSON 형태(itemType + 필드)를 그대로 역직렬화할 수 있어야 한다.
        String json = """
                {
                  "itemType": "MOVEMENT",
                  "fromPlace": "강남역",
                  "toPlace": "성수역",
                  "transportMode": "SUBWAY",
                  "lineName": "7호선"
                }
                """;

        TimelineItemPayload restored = objectMapper.readValue(json, TimelineItemPayload.class);

        assertThat(restored).isInstanceOf(MovementPayload.class);
        MovementPayload movement = (MovementPayload) restored;
        assertThat(movement.fromPlace()).isEqualTo("강남역");
        assertThat(movement.toPlace()).isEqualTo("성수역");
        assertThat(movement.transportMode()).isEqualTo("SUBWAY");
        assertThat(movement.lineName()).isEqualTo("7호선");
        assertThat(movement.itemType()).isEqualTo(ItemType.MOVEMENT);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
