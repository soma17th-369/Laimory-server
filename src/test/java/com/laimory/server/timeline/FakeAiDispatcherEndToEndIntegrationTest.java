package com.laimory.server.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.service.DailyRecordService;
import com.laimory.server.timeline.service.TimelineDraftEventSuggestionService;
import com.laimory.server.timeline.service.TimelineDraftSourceItemService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * fake AI dispatcher(app.ai.mode=fake)로 콜백 루프 전체를 실 HTTP로 검증하는 E2E:
 * POST 작성 작업 → fake가 delay 후 staging 커밋 + 자기 서버 콜백 HTTP 호출(토큰 검증·finalize) → 폴링 SUCCESS.
 * 공개 API 라우팅·envelope·콜백 컨트롤러 매핑(고정 URL과의 드리프트 포함)까지 이 테스트가 잡는다.
 *
 * <p>fake의 콜백 URL이 8080 고정이라 {@code DEFINED_PORT + server.port=8080}으로 실 서버를 띄운다 —
 * 실행 중 8080이 비어 있어야 한다(bootRun 동시 실행 불가).
 *
 * <p>실행: docker compose up -d 후 ./gradlew integrationTest
 */
// spring.http.client.read-timeout(전역 2s — fake AI dispatcher 등 블로킹 클라이언트용. 지오코딩은
// WebClient라 spring.http.reactiveclient.* 담당)이 TestRestTemplate에도 적용되는데,
// 컨텍스트 기동 직후 첫 POST는 워밍업(JIT·커넥션 풀)으로 2s를 간헐적으로 넘겨 flaky했다 → 이 컨텍스트만 완화.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"app.ai.mode=fake", "server.port=8080",
                "spring.http.client.connect-timeout=10s", "spring.http.client.read-timeout=10s"})
@ActiveProfiles("docker")
@Tag("integration")
class FakeAiDispatcherEndToEndIntegrationTest {

    private static final String TASKS = "/a/api/v1/timeline/drafts";
    // 다른 통합 테스트(2000-01-01)와 다른 고정 날짜로 격리. recordAt 정오 → 당일(2000-01-02).
    private static final LocalDate DATE = LocalDate.of(2000, 1, 2);

    // TimelineControllerTest.CREATE_BODY 형태의 복제(SourceItemDto는 itemType 기반 polymorphic payload).
    private static final String CREATE_BODY = """
            {
              "recordAt": "2000-01-02T12:00:00",
              "recordTimeZone": "Asia/Seoul",
              "sourceItems": [
                {"itemType": "PHOTO", "rawId": "0197b1c2-0000-7000-8000-000000000042",
                 "startAt": "2000-01-02T09:00:00", "endAt": null,
                 "payload": {"filename": "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "clientPhotoUri": "content://x",
                             "latitude": 1.0, "longitude": 2.0}}
              ]
            }
            """;

    // 2회 append 시나리오용 rawId(A/B/C). filename은 식별자가 아니므로 셋이 공유해도 무방(rawId가 정체성).
    private static final String RAW_A = "0197b1c2-0000-7000-8000-000000000042";
    private static final String RAW_B = "0197b1c2-0000-7000-8000-000000000043";
    private static final String RAW_C = "0197b1c2-0000-7000-8000-000000000044";

    // append#1: A(09:00) + B(09:30).
    private static final String APPEND1_BODY = """
            {
              "recordAt": "2000-01-02T12:00:00",
              "recordTimeZone": "Asia/Seoul",
              "sourceItems": [
                {"itemType": "PHOTO", "rawId": "0197b1c2-0000-7000-8000-000000000042",
                 "startAt": "2000-01-02T09:00:00", "endAt": null,
                 "payload": {"filename": "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "clientPhotoUri": "content://x",
                             "latitude": 1.0, "longitude": 2.0}},
                {"itemType": "PHOTO", "rawId": "0197b1c2-0000-7000-8000-000000000043",
                 "startAt": "2000-01-02T09:30:00", "endAt": null,
                 "payload": {"filename": "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "clientPhotoUri": "content://y",
                             "latitude": 1.0, "longitude": 2.0}}
              ]
            }
            """;

    // append#2: B(이미 저장) + C(신규 10:00) → B는 필터로 제외되고 C만 새 이벤트로 append.
    private static final String APPEND2_BODY = """
            {
              "recordAt": "2000-01-02T13:00:00",
              "recordTimeZone": "Asia/Seoul",
              "sourceItems": [
                {"itemType": "PHOTO", "rawId": "0197b1c2-0000-7000-8000-000000000043",
                 "startAt": "2000-01-02T09:30:00", "endAt": null,
                 "payload": {"filename": "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "clientPhotoUri": "content://y",
                             "latitude": 1.0, "longitude": 2.0}},
                {"itemType": "PHOTO", "rawId": "0197b1c2-0000-7000-8000-000000000044",
                 "startAt": "2000-01-02T10:00:00", "endAt": null,
                 "payload": {"filename": "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "clientPhotoUri": "content://z",
                             "latitude": 1.0, "longitude": 2.0}}
              ]
            }
            """;

    // append#3: 이미 저장된 B만 → 신규 0 → 409 ERROR_1013.
    private static final String APPEND3_BODY = """
            {
              "recordAt": "2000-01-02T14:00:00",
              "recordTimeZone": "Asia/Seoul",
              "sourceItems": [
                {"itemType": "PHOTO", "rawId": "0197b1c2-0000-7000-8000-000000000043",
                 "startAt": "2000-01-02T09:30:00", "endAt": null,
                 "payload": {"filename": "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "clientPhotoUri": "content://y",
                             "latitude": 1.0, "longitude": 2.0}}
              ]
            }
            """;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private DailyRecordService dailyRecordService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineDraftSourceItemService draftSourceItemService;
    @Autowired
    private TimelineDraftEventSuggestionService eventSuggestionService;
    @Autowired
    private RedisGateway redis;

    private final List<String> createdTaskIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        dailyRecordService.findByUserIdAndRecordDate(0L, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId())); // FK cascade
        createdTaskIds.forEach(id -> {
            draftSourceItemService.deleteByTaskId(id);
            eventSuggestionService.deleteByTaskId(id);
            redis.delete("timeline:draft-task:" + id);
        });
        createdTaskIds.clear();
        // 날짜 guard: 테스트가 terminal 전 실패하면 guard가 남아(운영 TTL 1h) 같은 고정 날짜의 후속 draft가 1016에 막힌다.
        redis.delete("timeline:date-guard:0:" + DATE);
    }

    @Test
    void draftPostThenPolling_runsFullCallbackLoopOverRealHttp() {
        // 1. 작성 작업 생성(실 HTTP) — 202 + envelope + taskId.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> created =
                restTemplate.postForEntity(TASKS, new HttpEntity<>(CREATE_BODY, headers), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(created.getBody().path("header").path("code").asText()).isEqualTo("COMMON_0000");
        String taskId = created.getBody().path("body").path("taskId").asText();
        assertThat(taskId).isNotBlank();
        createdTaskIds.add(taskId);

        // 2. fake의 콜백 delay(기본 2s) 동안은 PROCESSING — 앱이 로딩 상태를 관찰할 수 있다.
        //    PROCESSING에는 elapsedSeconds(AI 작업 대기 경과 시간)가 non-negative 숫자로 실린다.
        //    exact 초·폴링 간 대소는 검증하지 않는다(flaky 방지 — 정확 계산은 fixed Clock 단위 테스트 소유).
        JsonNode processingBody = poll(taskId).path("body");
        assertThat(processingBody.path("status").asText()).isEqualTo("PROCESSING");
        assertThat(processingBody.path("elapsedSeconds").isIntegralNumber()).isTrue();
        assertThat(processingBody.path("elapsedSeconds").asLong()).isGreaterThanOrEqualTo(0L);

        // 3. fake가 async로 staging 커밋 + 실 HTTP 콜백(토큰 검증→finalize) → SUCCESS 전이를 폴링으로 대기.
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(pollStatus(taskId)).isEqualTo("SUCCESS"));

        // 4. 결과 검증: canned 이벤트가 최종 타임라인으로 영속됐고 staging은 소비돼 비었다.
        JsonNode body = poll(taskId).path("body");
        JsonNode events = body.path("result").path("events");
        assertThat(events.size()).isEqualTo(1);
        assertThat(events.get(0).path("title").asText()).startsWith("[FAKE]"); // canned title 식별 표식
        // rawId는 요청 → draft → finalize → 폴링 응답까지 그대로 echo된다.
        assertThat(events.get(0).path("items").get(0).path("rawId").asText())
                .isEqualTo("0197b1c2-0000-7000-8000-000000000042");
        // photoUrl은 draft 저장 시 서버가 주입한 값이 finalize 복사를 거쳐 응답까지 유지된다.
        assertThat(events.get(0).path("items").get(0).path("payload").path("photoUrl").asText())
                .startsWith("https://");
        assertThat(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).isPresent();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isEmpty();
        assertThat(eventSuggestionService.findByTaskId(taskId)).isEmpty();

        // 5. 재폴링도 같은 결과(read-side 멱등). 경과 시간은 PROCESSING 전용 — SUCCESS 응답에는 key가 없다.
        JsonNode again = poll(taskId).path("body");
        assertThat(again.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(again.path("result").path("events").size()).isEqualTo(events.size());
        assertThat(again.has("elapsedSeconds")).isFalse();
    }

    @Test
    void secondAppend_excludesAlreadySavedRawId_andAppendsOnlyNewItem() {
        // append#1: A(042) + B(043) → fake가 1개 이벤트로 묶어 저장.
        String task1 = postAndAwaitSuccess(APPEND1_BODY);
        JsonNode firstEvents = poll(task1).path("body").path("result").path("events");
        assertThat(firstEvents.size()).isEqualTo(1);
        assertThat(rawIdsOf(firstEvents.get(0))).containsExactlyInAnyOrder(RAW_A, RAW_B);

        // append#2: B(이미 저장) + C(신규) → B는 rawId 필터로 제외, C만 새 이벤트로 append.
        String task2 = postAndAwaitSuccess(APPEND2_BODY);
        JsonNode events = poll(task2).path("body").path("result").path("events");
        assertThat(events.size()).isEqualTo(2); // 기존 이벤트 + 새 이벤트
        List<String> allRawIds = new ArrayList<>();
        events.forEach(ev -> allRawIds.addAll(rawIdsOf(ev)));
        // 그날 전체에서 B는 딱 한 번(재저장 안 됨), A·C도 각 한 번.
        assertThat(allRawIds).containsExactlyInAnyOrder(RAW_A, RAW_B, RAW_C);

        // append#3: 이미 저장된 B만 → 신규 0 → 동기 409 ERROR_1013(작업 생성 안 됨).
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> rejected =
                restTemplate.postForEntity(TASKS, new HttpEntity<>(APPEND3_BODY, headers), JsonNode.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.getBody().path("header").path("code").asText()).isEqualTo("ERROR_1013");
    }

    /** 작성 작업을 POST하고 SUCCESS까지 폴링 대기 후 taskId를 반환한다(cleanup 대상으로 등록). */
    private String postAndAwaitSuccess(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> created =
                restTemplate.postForEntity(TASKS, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String taskId = created.getBody().path("body").path("taskId").asText();
        assertThat(taskId).isNotBlank();
        createdTaskIds.add(taskId);
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(pollStatus(taskId)).isEqualTo("SUCCESS"));
        return taskId;
    }

    private static List<String> rawIdsOf(JsonNode event) {
        List<String> ids = new ArrayList<>();
        event.path("items").forEach(item -> ids.add(item.path("rawId").asText()));
        return ids;
    }

    private JsonNode poll(String taskId) {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(TASKS + "/" + taskId, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private String pollStatus(String taskId) {
        return poll(taskId).path("body").path("status").asText();
    }
}
