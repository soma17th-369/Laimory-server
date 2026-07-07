package com.laimory.server.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.common.redis.PrefixedRedis;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"app.ai.mode=fake", "server.port=8080"})
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
    private PrefixedRedis redis;

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
        assertThat(pollStatus(taskId)).isEqualTo("PROCESSING");

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
        assertThat(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).isPresent();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isEmpty();
        assertThat(eventSuggestionService.findByTaskId(taskId)).isEmpty();

        // 5. 재폴링도 같은 결과(read-side 멱등).
        JsonNode again = poll(taskId).path("body");
        assertThat(again.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(again.path("result").path("events").size()).isEqualTo(events.size());
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
