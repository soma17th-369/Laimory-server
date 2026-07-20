package com.laimory.server.timeline.payload;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.HealthMetric;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.CreateDraftTaskRequest;
import com.laimory.server.timeline.dto.SourceItemDto;
import java.util.List;
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
        String json = objectMapper.writeValueAsString(
                new PhotoPayload("u", "content://x", 1.0, 2.0, "설명", "https://cdn.example/u"));
        assertThat(json).doesNotContain("itemType");
        // photoUrl은 값이 있으면 직렬화된다(서버 주입 후 저장 JSON에 포함).
        assertThat(json).contains("\"photoUrl\":\"https://cdn.example/u\"");
    }

    @Test
    void calendarPayload_hasNoTypeInfo() throws Exception {
        String json = objectMapper.writeValueAsString(new CalendarPayload("주간 회의", "회의실 A", "설명", false));
        assertThat(json).doesNotContain("itemType");
    }

    @Test
    void healthPayload_hasNoTypeInfo() throws Exception {
        String json = objectMapper.writeValueAsString(new HealthPayload(HealthMetric.STEPS, "10145보"));
        assertThat(json).doesNotContain("itemType");
    }

    @Test
    void notificationPayload_hasNoTypeInfo() throws Exception {
        String json = objectMapper.writeValueAsString(new NotificationPayload("카카오톡", "제목", "내용"));
        assertThat(json).doesNotContain("itemType");
    }

    @Test
    void stayPayload_hasNoTypeInfo() throws Exception {
        String json = objectMapper.writeValueAsString(
                new StayPayload(37.5445, 127.0557, null, null, null));
        assertThat(json).doesNotContain("itemType");
    }

    @Test
    void movementPayload_hasNoTypeInfo() throws Exception {
        String json = objectMapper.writeValueAsString(movementFixture());
        assertThat(json).doesNotContain("itemType");
    }

    @Test
    void valueToTree_hasNoTypeInfo() {
        // 쓰기 경로(DailyTimelineService)가 쓰는 valueToTree에도 itemType이 없어야 한다.
        assertThat(objectMapper.valueToTree(movementFixture()).toString())
                .doesNotContain("itemType");
    }

    @Test
    void valueToTree_omitsNullFields_forAllPayloadTypes() {
        // 쓰기 경로(TimelineDraftTaskService의 valueToTree)에서 null 필드는 저장 JSON에 남지 않는다
        // (@JsonInclude(NON_NULL) — record 하나라도 애노테이션이 빠지면 여기서 잡힌다).
        List<TimelineItemPayload> payloads = List.of(
                new PhotoPayload("u", "c", null, null, null, null),
                new CalendarPayload("주간 회의", null, null, null),
                new StayPayload(37.5445, 127.0557, null, null, null),
                new MovementPayload(new MovementEndpoint(37.4979, 127.0276, null, null),
                        null, null, null),
                new HealthPayload(HealthMetric.STEPS, null),
                new NotificationPayload(null, "제목", null));
        for (TimelineItemPayload payload : payloads) {
            com.fasterxml.jackson.databind.JsonNode tree = objectMapper.valueToTree(payload);
            tree.fieldNames().forEachRemaining(name ->
                    assertThat(tree.get(name).isNull())
                            .as("%s.%s: null 필드는 직렬화에서 생략돼야 한다", payload.getClass().getSimpleName(), name)
                            .isFalse());
        }
    }

    @Test
    void movementEndpoint_omitsNullFields() {
        // 중첩 MovementEndpoint도 NON_NULL — enrich 전(null) 필드가 저장 JSON에 남지 않는다.
        com.fasterxml.jackson.databind.JsonNode tree =
                objectMapper.valueToTree(new MovementEndpoint(37.4979, 127.0276, null, null));
        assertThat(tree.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("latitude", "longitude");
    }

    // --- SourceItemDto external-property round-trip (itemType은 payload 형제) ---

    @Test
    void sourceItemDto_externalProperty_roundTrip_movement() throws Exception {
        String json = """
                {"itemType":"MOVEMENT","rawId":"0197b1c2-0000-7000-8000-000000000009",\
                 "startAt":"2026-05-08T08:30:00","endAt":null,\
                 "payload":{"start":{"latitude":37.4979,"longitude":127.0276},\
                            "end":{"latitude":37.5445,"longitude":127.0557},\
                            "transports":"IN_VEHICLE","distanceMeters":5200.0}}
                """;

        SourceItemDto dto = objectMapper.readValue(json, SourceItemDto.class);

        assertThat(dto.itemType()).isEqualTo(ItemType.MOVEMENT);
        // rawId는 itemType/startAt과 같은 envelope 형제 필드로 바인딩된다.
        assertThat(dto.rawId()).isEqualTo("0197b1c2-0000-7000-8000-000000000009");
        assertThat(dto.payload()).isInstanceOf(MovementPayload.class)
                .isEqualTo(new MovementPayload(
                        new MovementEndpoint(37.4979, 127.0276, null, null),
                        new MovementEndpoint(37.5445, 127.0557, null, null),
                        "IN_VEHICLE", 5200.0));
        assertThat(dto.startAt()).isEqualTo(java.time.LocalDateTime.of(2026, 5, 8, 8, 30));
    }

    @Test
    void sourceItemDto_externalProperty_roundTrip_photo() throws Exception {
        String json = """
                {"itemType":"PHOTO","startAt":"2026-06-17T09:00:00","endAt":null,\
                 "payload":{"filename":"u","clientPhotoUri":"c","latitude":1.0,"longitude":2.0,\
                            "photoUrl":"https://cdn.example/u"}}
                """;

        SourceItemDto dto = objectMapper.readValue(json, SourceItemDto.class);

        assertThat(dto.itemType()).isEqualTo(ItemType.PHOTO);
        assertThat(dto.payload()).isInstanceOf(PhotoPayload.class)
                .isEqualTo(new PhotoPayload("u", "c", 1.0, 2.0, null, "https://cdn.example/u"));
    }

    @Test
    void sourceItemDto_externalProperty_roundTrip_health() throws Exception {
        String json = """
                {"itemType":"HEALTH","startAt":"2026-06-30T00:00:00","endAt":"2026-07-01T00:00:00",\
                 "payload":{"metric":"STEPS","value":"10145보"}}
                """;

        SourceItemDto dto = objectMapper.readValue(json, SourceItemDto.class);

        assertThat(dto.itemType()).isEqualTo(ItemType.HEALTH);
        assertThat(dto.payload()).isInstanceOf(HealthPayload.class)
                .isEqualTo(new HealthPayload(HealthMetric.STEPS, "10145보"));
    }

    @Test
    void sourceItemDto_externalProperty_roundTrip_healthSleep() throws Exception {
        // SLEEP도 value 하나로 — value는 단위 포함 텍스트다.
        String json = """
                {"itemType":"HEALTH","startAt":"2026-06-30T04:00:00","endAt":"2026-06-30T07:30:00",\
                 "payload":{"metric":"SLEEP","value":"210분"}}
                """;

        SourceItemDto dto = objectMapper.readValue(json, SourceItemDto.class);

        assertThat(dto.payload()).isInstanceOf(HealthPayload.class)
                .isEqualTo(new HealthPayload(HealthMetric.SLEEP, "210분"));
    }

    @Test
    void sourceItemDto_externalProperty_roundTrip_notification() throws Exception {
        String json = """
                {"itemType":"NOTIFICATION","startAt":"2026-06-30T21:12:00","endAt":null,\
                 "payload":{"appName":"카카오톡","title":"[소마] 정수현 님","text":"내일 몇시 도착이신가요?"}}
                """;

        SourceItemDto dto = objectMapper.readValue(json, SourceItemDto.class);

        assertThat(dto.itemType()).isEqualTo(ItemType.NOTIFICATION);
        assertThat(dto.payload()).isInstanceOf(NotificationPayload.class)
                .isEqualTo(new NotificationPayload("카카오톡", "[소마] 정수현 님", "내일 몇시 도착이신가요?"));
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
                .isEqualTo(new PhotoPayload("u", "c", 1.0, 2.0, null, null));
        assertThat(dto.itemType()).isEqualTo(ItemType.PHOTO);
    }

    @Test
    void createDraftTaskRequest_nested_itemTypeAfterPayload() throws Exception {
        // 컨트롤러가 역직렬화하는 실제 요청 바디 형태: sourceItems 배열의 각 아이템에서
        // payload가 itemType보다 먼저 온다. 중첩 컨텍스트에서도 외부 프로퍼티 버퍼링이 동작해야 한다.
        String json = """
                {"recordDate":"2026-05-08","recordAt":"2026-05-08T12:30:00","recordTimeZone":"Asia/Seoul",
                 "timelineWindow":{"startTime":"2026-05-08T00:00","endTime":"2026-05-09T00:00"},"sourceItems":[
                  {"startAt":null,"endAt":null,\
                   "payload":{"start":{"latitude":37.4979,"longitude":127.0276},\
                              "end":{"latitude":37.5445,"longitude":127.0557},\
                              "transports":"IN_VEHICLE"},
                   "itemType":"MOVEMENT"}
                ]}
                """;

        CreateDraftTaskRequest req = objectMapper.readValue(json, CreateDraftTaskRequest.class);

        assertThat(req.sourceItems().get(0).payload()).isInstanceOf(MovementPayload.class)
                .isEqualTo(movementFixture());
        assertThat(req.sourceItems().get(0).itemType()).isEqualTo(ItemType.MOVEMENT);
    }

    @Test
    void sourceItemDto_serializes_itemTypeAsSiblingOfPayload() throws Exception {
        SourceItemDto dto = new SourceItemDto(
                ItemType.MOVEMENT, "raw-mov",
                java.time.LocalDateTime.of(2026, 5, 8, 8, 30), null,
                movementFixture());

        com.fasterxml.jackson.databind.JsonNode tree = objectMapper.valueToTree(dto);

        // itemType·rawId는 최상위(payload 형제)에 있고, payload 안엔 없다.
        assertThat(tree.has("itemType")).isTrue();
        assertThat(tree.get("itemType").asText()).isEqualTo("MOVEMENT");
        assertThat(tree.get("payload").has("itemType")).isFalse();
        assertThat(tree.get("rawId").asText()).isEqualTo("raw-mov");
        assertThat(tree.get("payload").has("rawId")).isFalse();
    }

    private static MovementPayload movementFixture() {
        return new MovementPayload(
                new MovementEndpoint(37.4979, 127.0276, null, null),
                new MovementEndpoint(37.5445, 127.0557, null, null),
                "IN_VEHICLE", null);
    }
}
