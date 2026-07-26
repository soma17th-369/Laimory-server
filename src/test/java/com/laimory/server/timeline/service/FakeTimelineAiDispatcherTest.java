package com.laimory.server.timeline.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.service.FakeAiTimelineAppendService.AppendResult;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * fake AI 디스패처 단위 검증: append(direct-write 대행) 결과에 따라 자기 서버 콜백 엔드포인트로
 * SUCCESS/FAILED를 실제 HTTP 형태(MockRestServiceServer)로 보내는 계약을 고정한다.
 * delay는 ZERO 주입으로 무력화하고 dispatch를 직접 호출한다(@Async 프록시는 배선 테스트가 검증).
 */
@ExtendWith(MockitoExtension.class)
class FakeTimelineAiDispatcherTest {

    @Mock
    private FakeAiTimelineAppendService fakeAiTimelineAppendService;

    private MockRestServiceServer server;
    private FakeTimelineAiDispatcher dispatcher;
    private SimpleMeterRegistry meterRegistry;

    private static final String CALLBACK_URL = "http://localhost:8080/s/api/v1/timeline/drafts/task-1/callback";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        RestClient.Builder builder = RestClient.builder().observationRegistry(observationRegistry);
        server = MockRestServiceServer.bindTo(builder).build();
        dispatcher = new FakeTimelineAiDispatcher(fakeAiTimelineAppendService, builder, Duration.ZERO);
    }

    private AiTimelineDispatchRequest request() {
        ZoneOffset kst = ZoneOffset.ofHours(9);
        return new AiTimelineDispatchRequest("task-1", "raw-token", 42L,
                new AiTimelineDispatchRequest.Window(
                        OffsetDateTime.of(2026, 6, 17, 0, 0, 0, 0, kst),
                        OffsetDateTime.of(2026, 6, 18, 0, 0, 0, 0, kst)));
    }

    @Test
    void dispatch_appendSuccess_postsSuccessCallbackWithToken() {
        when(fakeAiTimelineAppendService.append("task-1", 42L)).thenReturn(AppendResult.SUCCESS);
        server.expect(requestTo(CALLBACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Callback-Token", "raw-token"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andRespond(withSuccess());

        dispatcher.dispatch(request());

        server.verify();
    }

    @Test
    void dispatch_appendValidationFailed_postsFailedCallbackWith1008() {
        when(fakeAiTimelineAppendService.append("task-1", 42L)).thenReturn(AppendResult.VALIDATION_FAILED);
        server.expect(requestTo(CALLBACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Callback-Token", "raw-token"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value(-1008))
                .andRespond(withSuccess());

        dispatcher.dispatch(request());

        server.verify();
    }

    @Test
    void dispatch_appendThrows_postsFailedCallback() {
        // append 예외(DB 오류 등)도 FAILED 콜백으로 보고한다 — task가 PROCESSING에 갇히지 않게.
        when(fakeAiTimelineAppendService.append(anyString(), anyLong()))
                .thenThrow(new RuntimeException("db down"));
        server.expect(requestTo(CALLBACK_URL))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andRespond(withSuccess());

        Assertions.assertThatCode(() -> dispatcher.dispatch(request())).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void dispatch_callbackHttpFailure_isSwallowed() {
        // 콜백 실패는 삼킨다(재시도 없음 — dev 도구). task는 PROCESSING TTL로 소멸하고 final graph는 commit대로 남는다.
        when(fakeAiTimelineAppendService.append("task-1", 42L)).thenReturn(AppendResult.SUCCESS);
        server.expect(requestTo(CALLBACK_URL)).andRespond(withServerError());

        Assertions.assertThatCode(() -> dispatcher.dispatch(request())).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void dispatch_httpMetricDoesNotUseTaskIdAsTag() {
        when(fakeAiTimelineAppendService.append("task-1", 42L)).thenReturn(AppendResult.SUCCESS);
        server.expect(requestTo(CALLBACK_URL)).andRespond(withSuccess());

        dispatcher.dispatch(request());

        assertThatHttpMetricTagsDoNotContain("task-1");
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
