package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import com.laimory.server.timeline.dto.TimelineAiTestAiRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

/**
 * AI 동기 테스트 client 계약 검증 — MockWebServer 실 HTTP 루프백으로 request/response fixture를 고정한다.
 *
 * <p>고정하는 계약: 나가는 body에 {@code taskId}는 있고 {@code taskToken}은 <b>없다</b>,
 * {@code X-Timeline-Timed-Out}은 성공 신호로 전달된다, AI 오류의 <b>numeric errorCode만</b> 꺼내고
 * 자유 text {@code error}는 어디에도 남기지 않는다, 응답이 없거나 계약을 어기면 502 계열로 끝난다,
 * 그리고 어떤 실패에서도 <b>재시도하지 않는다</b>(호출 1회 = LLM 토큰 비용 1회).
 *
 * <p>생성자 검증(URL 형식·timeout·요청 상한)도 함께 고정한다 — {@link HttpTimelineAiDispatcher}와 같이
 * 설정 오류를 첫 호출이 아니라 기동에서 잡기 때문이다.
 */
class TimelineAiTestClientTest {

    // production과 같은 날짜 직렬화를 쓴다 — Boot가 끄는 WRITE_DATES_AS_TIMESTAMPS를 여기서도 꺼야
    // @JsonFormat이 없는 recordDate가 배열이 아니라 "2026-06-20"으로 나간다(AI 계약).
    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final String TASK_ID = "0198f2a1-7c3d-7000-8b2e-1f4a9c05d6e7";
    private static final String RAW_ID = "6b5f2d3e-9c1a-4f88-9a2b-2f0d5c7e1a34";
    private static final String AI_ERROR_TEXT = "RAW_AI_ERROR_394_NEVER_EXPOSE";
    private static final Duration VALID_READ_TIMEOUT = Duration.ofSeconds(150);
    private static final String RESULT_BODY = """
            {"events":[{"eventType":"MEAL","title":"점심","subtitle":"근처 식당에서 식사했어요.",
              "place":"회사","address":null,
              "startAt":"2026-06-20T12:00:00+09:00","endAt":"2026-06-20T13:00:00+09:00",
              "sourceRawIds":["%s"],"question":"어떤 이야기가 기억에 남나요?"}]}
            """.formatted(RAW_ID);

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    // --- 생성자 fail-fast ---

    @Test
    void constructorRejectsUnusableConfiguration() {
        assertThatThrownBy(() -> newClient("  ", VALID_READ_TIMEOUT, 1024))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> newClient("/relative/path", VALID_READ_TIMEOUT, 1024))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> newClient("ftp://ai.example/v1", VALID_READ_TIMEOUT, 1024))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> newClient(url(), VALID_READ_TIMEOUT, 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constructorRejectsReadTimeoutThatWouldCutOffTheAiPipeline() {
        // AI는 PIPELINE_TIMEOUT_SEC(120s)이 끝나면 X-Timeline-Timed-Out과 함께 정상 200을 준다 —
        // read timeout이 그보다 짧거나 같으면 성공 응답을 받기 직전에 끊어 502로 만든다.
        assertThatThrownBy(() -> newClient(url(), TimelineAiTestClient.AI_PIPELINE_TIMEOUT, 1024))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> newClient(url(), Duration.ofSeconds(30), 1024))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> newClient(url(), Duration.ofSeconds(121), 1024))
                .doesNotThrowAnyException();
    }

    // --- 성공 경로 ---

    @Test
    void sendsTaskIdWithoutTaskTokenAndReturnsAiResult() throws Exception {
        server.enqueue(json(200, RESULT_BODY));

        TimelineAiTestOutcome outcome = client().generate(request());

        assertThat(outcome.taskId()).isEqualTo(TASK_ID);
        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.events()).hasSize(1);
        assertThat(outcome.events().getFirst().title()).isEqualTo("점심");
        assertThat(outcome.events().getFirst().sourceRawIds()).containsExactly(RAW_ID);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        JsonNode sent = MAPPER.readTree(recorded.getBody().readUtf8());
        assertThat(sent.get("taskId").asText()).isEqualTo(TASK_ID);
        // AI가 App Server를 되부르지 않으므로 토큰 자체가 계약에 없다.
        assertThat(sent.has("taskToken")).isFalse();
        assertThat(sent.get("recordDate").asText()).isEqualTo("2026-06-20");
        assertThat(sent.at("/window/startAt").asText()).isEqualTo("2026-06-20T00:00:00+09:00");
        assertThat(sent.at("/sourceItems/0/itemType").asText()).isEqualTo("STAY");
    }

    @Test
    void omitsOptionalKeysWhenAbsent() throws Exception {
        // recordTimeZone 기본값(Asia/Seoul)은 AI가 소유한다 — 명시적 null 대신 key를 생략해 전달한다.
        server.enqueue(json(200, RESULT_BODY));

        client().generate(new TimelineAiTestAiRequest(
                TASK_ID, LocalDate.of(2026, 6, 20), null, window(), null, List.of(sourceItem())));

        JsonNode sent = MAPPER.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(sent.has("recordTimeZone")).isFalse();
        assertThat(sent.has("userMemory")).isFalse();
    }

    @Test
    void propagatesTimedOutHeaderAsSuccessSignal() {
        server.enqueue(json(200, RESULT_BODY).setHeader(TimelineAiTestClient.TIMED_OUT_HEADER, "true"));

        TimelineAiTestOutcome outcome = client().generate(request());

        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.events()).hasSize(1);
    }

    // --- AI 오류 응답 ---

    @Test
    void surfacesNumericAiErrorCodeWithoutFreeText() {
        server.enqueue(json(500, "{\"errorCode\":1301,\"error\":\"" + AI_ERROR_TEXT + "\"}"));

        assertThatThrownBy(() -> client().generate(request()))
                .isInstanceOfSatisfying(TimelineAiTestCallException.class, e -> {
                    assertThat(e.getAiStatus()).isEqualTo(500);
                    assertThat(e.getAiErrorCode()).isEqualTo(1301);
                    // 자유 text error는 사용자 원문이 섞일 수 있어 예외 어디에도 담지 않는다.
                    assertThat(e.getReason()).doesNotContain(AI_ERROR_TEXT);
                })
                .hasMessageNotContaining(AI_ERROR_TEXT);
    }

    @Test
    void classifiesAiClientErrorsWithTheirCodes() {
        server.enqueue(json(422, "{\"errorCode\":1001,\"error\":\"window required\"}"));
        assertThatThrownBy(() -> client().generate(request()))
                .isInstanceOfSatisfying(TimelineAiTestCallException.class,
                        e -> assertThat(e.getAiErrorCode()).isEqualTo(1001));

        // AI 쪽이 닫혀 있다는 404 — 우리 404(경로 없음)와 의미가 다르므로 code로 구분된다.
        server.enqueue(json(404, "{\"errorCode\":1003,\"error\":\"disabled\"}"));
        assertThatThrownBy(() -> client().generate(request()))
                .isInstanceOfSatisfying(TimelineAiTestCallException.class, e -> {
                    assertThat(e.getAiStatus()).isEqualTo(404);
                    assertThat(e.getAiErrorCode()).isEqualTo(1003);
                });
    }

    @Test
    void leavesErrorCodeNullWhenAiBodyIsNotContractShaped() {
        server.enqueue(json(500, "not json at all"));

        assertThatThrownBy(() -> client().generate(request()))
                .isInstanceOfSatisfying(TimelineAiTestCallException.class, e -> {
                    assertThat(e.getAiStatus()).isEqualTo(500);
                    assertThat(e.getAiErrorCode()).isNull();
                });
    }

    // --- 성공 status인데 계약을 어긴 응답 ---

    @Test
    void rejectsNonJsonSuccessBody() {
        server.enqueue(json(200, "<html>not json</html>"));

        assertThatThrownBy(() -> client().generate(request()))
                .isInstanceOf(TimelineAiTestCallException.class);
    }

    @Test
    void rejectsResultContractViolations() {
        server.enqueue(json(200, "{\"events\":[]}"));
        assertThatThrownBy(() -> client().generate(request()))
                .isInstanceOf(TimelineAiTestCallException.class);

        // 필수 필드(title) 누락 — 200이어도 계약 위반이면 통과시키지 않는다.
        server.enqueue(json(200, "{\"events\":[{\"eventType\":\"MEAL\",\"title\":\"  \","
                + "\"startAt\":\"2026-06-20T12:00:00+09:00\",\"sourceRawIds\":[\"" + RAW_ID + "\"]}]}"));
        assertThatThrownBy(() -> client().generate(request()))
                .isInstanceOf(TimelineAiTestCallException.class);
    }

    // --- 크기 상한 ---

    @Test
    void rejectsOversizedRequestBeforeCallingAi() {
        // 전송 전에 끊으므로 AI를 부르지 않는다 — 토큰 비용도 발생하지 않는다.
        assertThatThrownBy(() -> newClient(url(), VALID_READ_TIMEOUT, 16).generate(request()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    // --- AI 응답 자체가 없는 실패 ---

    @Test
    void transportFailureFailsWithoutStatusOrErrorCodeAndWithoutRetry() {
        // read timeout·connect 실패·연결 끊김은 모두 "AI 응답 자체가 없음"이라 같은 분기다. timeout은
        // 생성자가 120s 초과를 강제해 테스트에서 재현하기 어려우므로 연결 끊김으로 그 분기를 고정한다.
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        assertThatThrownBy(() -> client().generate(request()))
                .isInstanceOfSatisfying(TimelineAiTestCallException.class, e -> {
                    // 헤더 유무가 "AI가 답을 하긴 했는가"의 신호라, 응답이 없으면 둘 다 null이어야 한다.
                    assertThat(e.getAiStatus()).isNull();
                    assertThat(e.getAiErrorCode()).isNull();
                });
        // 재시도하지 않는다 — 호출 1회가 LLM 토큰 비용 1회다.
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // --- fixtures ---

    private TimelineAiTestClient client() {
        return newClient(url(), VALID_READ_TIMEOUT, 1024 * 1024);
    }

    private String url() {
        return server.url("/v1/timeline/test").toString();
    }

    private static TimelineAiTestClient newClient(String url, Duration readTimeout, int maxRequestBytes) {
        return new TimelineAiTestClient(RestClient.builder(), MAPPER, url, Duration.ofSeconds(2),
                readTimeout, DataSize.ofBytes(maxRequestBytes));
    }

    private static TimelineAiTestAiRequest request() {
        return new TimelineAiTestAiRequest(TASK_ID, LocalDate.of(2026, 6, 20), "Asia/Seoul",
                window(), null, List.of(sourceItem()));
    }

    private static AiTimelineTaskInputResponse.Window window() {
        return new AiTimelineTaskInputResponse.Window(
                OffsetDateTime.of(2026, 6, 20, 0, 0, 0, 0, KST),
                OffsetDateTime.of(2026, 6, 21, 0, 0, 0, 0, KST));
    }

    private static AiTimelineTaskInputResponse.SourceItem sourceItem() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("latitude", 37.5);
        payload.put("longitude", 127.0);
        return new AiTimelineTaskInputResponse.SourceItem(RAW_ID, ItemType.STAY,
                OffsetDateTime.of(2026, 6, 20, 12, 0, 0, 0, KST),
                OffsetDateTime.of(2026, 6, 20, 13, 0, 0, 0, KST), payload);
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
