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

/**
 * 실 AI HTTP 디스패처 계약 검증 — MockWebServer 실 HTTP 루프백으로 request/response fixture를 고정한다.
 * body 필드명·offset ISO 포맷은 AI 규격이 명명 권위인 공개 계약이라 정확 문자열로 단언한다(드리프트 방지).
 *
 * <p>실패 분류도 함께 고정한다: <b>4xx만</b> 미접수 확정({@link TimelineAiDispatchRejectedException})이고,
 * 5xx·read timeout·계약 불일치는 UNKNOWN이라 그 타입이 <b>아닌</b> 예외로 전파돼야 한다(호출부가 FAILED로
 * 확정하지 않도록).
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
        return new AiTimelineDispatchRequest("task-20260722-001", "task-token-001", 42L,
                new AiTimelineDispatchRequest.Window(
                        OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, kst),
                        OffsetDateTime.of(2026, 7, 23, 0, 0, 0, 0, kst)));
    }

    private MockResponse accepted(String taskId, String status) {
        return new MockResponse().setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"taskId\":\"%s\",\"status\":\"%s\"}".formatted(taskId, status));
    }

    // --- base-url fail-fast(생성자 검증) ---

    @Test
    void constructor_blankBaseUrl_failsFast() {
        assertThatThrownBy(() -> new HttpTimelineAiDispatcher(RestClient.builder(), "  ",
                Duration.ofSeconds(2), Duration.ofMillis(500)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    void constructor_nonAbsoluteBaseUrl_failsFast() {
        // 스킴 없는 상대 URI는 첫 dispatch에서야 터지므로 기동 시점에 거절한다.
        assertThatThrownBy(() -> new HttpTimelineAiDispatcher(RestClient.builder(), "localhost:8000",
                Duration.ofSeconds(2), Duration.ofMillis(500)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absolute http");
    }

    @Test
    void constructor_ackWaitReachingHalfProcessingTtl_failsFast() {
        // task는 dispatch 전에 TTL(3m)로 저장되므로 접수 대기 상한(connect+read 합)이 수명에 근접하면
        // 유효한 202를 받고도 만료된 taskId를 반환할 수 있다 — 합 90s(TTL 절반) 이상은 기동 시 거부한다.
        assertThatThrownBy(() -> new HttpTimelineAiDispatcher(RestClient.builder(),
                server.url("/").toString(), Duration.ofSeconds(45), Duration.ofSeconds(45)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROCESSING TTL");
        assertThatCode(() -> new HttpTimelineAiDispatcher(RestClient.builder(),
                server.url("/").toString(), Duration.ofSeconds(45), Duration.ofMillis(44_999)))
                .doesNotThrowAnyException();
    }

    // --- 정상 접수 ---

    @Test
    void dispatch_postsContractBodyToV1Timeline() throws Exception {
        server.enqueue(accepted("task-20260722-001", "PROCESSING"));

        dispatcher.dispatch(request());

        RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/v1/timeline");
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json");

        // contract fixture: 기존 필드명·offset ISO-8601 초 포함 포맷을 정확히 고정한다.
        JsonNode body = MAPPER.readTree(recorded.getBody().readUtf8());
        assertThat(body.get("taskId").asText()).isEqualTo("task-20260722-001");
        assertThat(body.get("taskToken").asText()).isEqualTo("task-token-001");
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

    // --- 미접수 확정(4xx만) ---

    @Test
    void dispatch_schemaError422_throwsRejected() {
        // FastAPI 표준 422(필수 필드 누락·offset 파싱 실패) = 미접수 확정 → Rejected로 던져 호출부가 FAILED 종결.
        server.enqueue(new MockResponse().setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":[{\"loc\":[\"body\",\"window\"],\"msg\":\"field required\"}]}"));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(TimelineAiDispatchRejectedException.class);
    }

    @Test
    void dispatch_otherClientError4xx_throwsRejected() {
        server.enqueue(new MockResponse().setResponseCode(400));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(TimelineAiDispatchRejectedException.class);
    }

    // --- UNKNOWN(Rejected가 아니어야 함) ---

    @Test
    void dispatch_serverError5xx_isUnknown_notRejected() {
        // 5xx는 접수 이후 발생했을 수 있어 미접수를 확정하지 못한다 → Rejected가 아닌 예외로 전파(UNKNOWN).
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class);
    }

    @Test
    void dispatch_readTimeout_isUnknown_notRejected() {
        // read timeout(테스트 500ms 초과) — AI가 접수해 처리 중일 수 있으므로 미접수로 확정하지 않는다.
        server.enqueue(accepted("task-20260722-001", "PROCESSING").setBodyDelay(2, TimeUnit.SECONDS));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class);
    }

    @Test
    void dispatch_mismatchedTaskId_isUnknown_notRejected() {
        // 응답은 받았지만 계약 불일치 = 접수 여부 불명 → Rejected가 아닌 IllegalStateException(UNKNOWN).
        server.enqueue(accepted("other-task", "PROCESSING"));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class)
                .hasMessageContaining("AI 접수 계약 불일치");
    }

    @Test
    void dispatch_nonProcessingStatus_isUnknown_notRejected() {
        server.enqueue(accepted("task-20260722-001", "DONE"));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class);
    }

    @Test
    void dispatch_non202Success_isUnknown_notRejected() {
        // 200 등 다른 2xx도 202 계약 위반 = UNKNOWN(미접수로 확정하지 않음).
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"taskId\":\"task-20260722-001\",\"status\":\"PROCESSING\"}"));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class);
    }
}
