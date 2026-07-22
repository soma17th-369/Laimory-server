package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 실 AI HTTP 디스패처 계약 검증 — MockWebServer 실 HTTP 루프백으로 request/response fixture를 고정한다.
 * body 필드명·offset ISO 포맷은 AI 규격이 명명 권위인 공개 계약이라 정확 문자열로 단언한다(드리프트 방지).
 */
class HttpTimelineAiDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer server;
    private HttpTimelineAiDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        dispatcher = new HttpTimelineAiDispatcher(RestClient.builder(),
                server.url("/").toString(), Duration.ofSeconds(2), Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private AiTimelineDispatchRequest request() {
        ZoneOffset kst = ZoneOffset.ofHours(9);
        return new AiTimelineDispatchRequest("task-20260722-001", "callback-token-001", 42L,
                new AiTimelineDispatchRequest.Window(
                        OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, kst),
                        OffsetDateTime.of(2026, 7, 23, 0, 0, 0, 0, kst)));
    }

    private MockResponse accepted(String taskId, String status) {
        return new MockResponse().setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"taskId\":\"%s\",\"status\":\"%s\"}".formatted(taskId, status));
    }

    @Test
    void dispatch_postsContractBodyToV1Timeline() throws Exception {
        server.enqueue(accepted("task-20260722-001", "PROCESSING"));

        dispatcher.dispatch(request());

        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/v1/timeline");
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json");

        // contract fixture: 필드명·offset ISO-8601 초 포함 포맷을 정확히 고정한다(양 저장소 공통 계약).
        JsonNode body = MAPPER.readTree(recorded.getBody().readUtf8());
        assertThat(body.get("taskId").asText()).isEqualTo("task-20260722-001");
        assertThat(body.get("callbackToken").asText()).isEqualTo("callback-token-001");
        assertThat(body.get("dailyRecordId").asLong()).isEqualTo(42L);
        assertThat(body.get("window").get("startAt").asText()).isEqualTo("2026-07-22T00:00:00+09:00");
        assertThat(body.get("window").get("endAt").asText()).isEqualTo("2026-07-23T00:00:00+09:00");
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.get("window").size()).isEqualTo(2);
    }

    @Test
    void dispatch_accepts202WithMatchingTaskIdAndProcessing() {
        server.enqueue(accepted("task-20260722-001", "PROCESSING"));

        assertThatCode(() -> dispatcher.dispatch(request())).doesNotThrowAnyException();
    }

    @Test
    void dispatch_rejectsMismatchedTaskId() {
        server.enqueue(accepted("other-task", "PROCESSING"));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI 접수 계약 불일치");
    }

    @Test
    void dispatch_rejectsNonProcessingStatus() {
        server.enqueue(accepted("task-20260722-001", "DONE"));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dispatch_rejectsNon202Success() {
        // 200 등 다른 2xx도 접수 계약 위반이다(202 고정).
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"taskId\":\"task-20260722-001\",\"status\":\"PROCESSING\"}"));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dispatch_schemaError422_throwsRestClientException() {
        // FastAPI 표준 422(필수 필드 누락·offset 파싱 실패) — RestClient 기본 예외로 전파돼 호출부가 FAILED 종결.
        server.enqueue(new MockResponse().setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":[{\"loc\":[\"body\",\"window\"],\"msg\":\"field required\"}]}"));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(RestClientResponseException.class);
    }

    @Test
    void dispatch_readTimeout_throws() {
        // AI 접수는 202 즉시 반환 계약 — read timeout(테스트 500ms) 초과 응답은 접수 실패로 던진다.
        server.enqueue(accepted("task-20260722-001", "PROCESSING").setBodyDelay(2, TimeUnit.SECONDS));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(RuntimeException.class);
    }
}
