package com.laimory.server.timeline.service;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * fake AI 디스패처 단위 검증: 실 AI와 같은 순서(입력 조회 → 결과 저장 → 콜백)로 자기 서버의 서버간
 * 엔드포인트를 호출하는 계약을 실제 HTTP 형태(MockRestServiceServer)로 고정한다. 단일 task token이
 * 세 요청에 공통으로 쓰이는지도 함께 본다.
 * delay는 ZERO 주입으로 무력화하고 dispatch를 직접 호출한다(@Async 프록시는 배선 테스트가 검증).
 */
class FakeTimelineAiDispatcherTest {

    private MockRestServiceServer server;
    private FakeTimelineAiDispatcher dispatcher;
    private SimpleMeterRegistry meterRegistry;

    private static final String TASK_ID = "task-1";
    private static final String BASE_URL = "http://localhost:8080/s/api/v1/timeline/drafts/" + TASK_ID;
    private static final String INPUT_URL = BASE_URL + "/input";
    private static final String RESULT_URL = BASE_URL + "/result";
    private static final String CALLBACK_URL = BASE_URL + "/callback";

    private static final String INPUT_TOKEN = "raw-input-token";

    private static final String INPUT_BODY = """
            {
              "taskId": "task-1",
              "recordDate": "2026-06-17",
              "recordTimeZone": "Asia/Seoul",
              "window": {"startAt": "2026-06-17T00:00:00+09:00", "endAt": "2026-06-18T00:00:00+09:00"},
              "sourceItems": [
                {"rawId": "raw-1", "itemType": "CALENDAR",
                 "startAt": "2026-06-17T09:00:00+09:00", "endAt": "2026-06-17T10:00:00+09:00",
                 "payload": {"title": "스탠드업"}},
                {"rawId": "raw-2", "itemType": "NOTIFICATION",
                 "startAt": "2026-06-17T11:00:00+09:00", "endAt": null,
                 "payload": {"title": "알림"}}
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        RestClient.Builder builder = RestClient.builder().observationRegistry(observationRegistry);
        server = MockRestServiceServer.bindTo(builder).build();
        dispatcher = new FakeTimelineAiDispatcher(builder, Duration.ZERO);
    }

    private AiTimelineDispatchRequest request() {
        return new AiTimelineDispatchRequest(TASK_ID, INPUT_TOKEN);
    }

    private void expectInput() {
        server.expect(requestTo(INPUT_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Task-Token", INPUT_TOKEN))
                .andRespond(withSuccess(INPUT_BODY, MediaType.APPLICATION_JSON));
    }

    @Test
    void dispatch_storesResultThenPostsSuccessCallback() {
        expectInput();
        // 조회한 source 전부가 Event 하나로 묶이고 dispatch와 같은 token을 쓴다.
        server.expect(requestTo(RESULT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Task-Token", INPUT_TOKEN))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].eventType").value("UNKNOWN"))
                // fake는 조회한 시각을 그대로 되돌려 보낸다. Jackson 역직렬화가 offset을 UTC로 옮기므로
                // 표기는 Z이지만 같은 순간이며, 서버가 record timezone wall-clock으로 정규화한다.
                .andExpect(jsonPath("$.events[0].startAt").value("2026-06-17T00:00:00Z"))
                .andExpect(jsonPath("$.events[0].endAt").value("2026-06-17T01:00:00Z"))
                .andExpect(jsonPath("$.events[0].sourceRawIds[0]").value("raw-1"))
                .andExpect(jsonPath("$.events[0].sourceRawIds[1]").value("raw-2"))
                .andRespond(withSuccess());
        // 콜백도 같은 task token을 쓴다.
        server.expect(requestTo(CALLBACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Task-Token", INPUT_TOKEN))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andRespond(withSuccess());

        dispatcher.dispatch(request());

        server.verify();
    }

    @Test
    void dispatch_inputFails_postsFailedCallbackWithSameTaskToken() {
        server.expect(requestTo(INPUT_URL)).andRespond(withServerError());
        server.expect(requestTo(CALLBACK_URL))
                .andExpect(header("Task-Token", INPUT_TOKEN))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value(-1008))
                .andRespond(withSuccess());

        Assertions.assertThatCode(() -> dispatcher.dispatch(request())).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void dispatch_resultStoreFails_postsFailedCallback() {
        expectInput();
        server.expect(requestTo(RESULT_URL)).andRespond(withServerError());
        server.expect(requestTo(CALLBACK_URL))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andRespond(withSuccess());

        Assertions.assertThatCode(() -> dispatcher.dispatch(request())).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void dispatch_callbackHttpFailure_isSwallowed() {
        // 콜백 실패는 삼킨다(재시도 없음 — dev 도구). task는 PROCESSING TTL로 소멸하고 저장된 graph는 남는다.
        expectInput();
        server.expect(requestTo(RESULT_URL)).andRespond(withSuccess());
        server.expect(requestTo(CALLBACK_URL)).andRespond(withServerError());

        Assertions.assertThatCode(() -> dispatcher.dispatch(request())).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void dispatch_httpMetricDoesNotUseTaskIdAsTag() {
        expectInput();
        server.expect(requestTo(RESULT_URL)).andRespond(withSuccess());
        server.expect(requestTo(CALLBACK_URL)).andRespond(withSuccess());

        dispatcher.dispatch(request());

        assertThatHttpMetricTagsDoNotContain(TASK_ID);
    }

    private void assertThatHttpMetricTagsDoNotContain(String forbiddenValue) {
        Assertions.assertThat(meterRegistry.getMeters())
                .filteredOn(meter -> meter.getId().getName().startsWith("http.client.requests"))
                .isNotEmpty()
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getValue())
                .noneMatch(value -> value.contains(forbiddenValue));
    }
}
