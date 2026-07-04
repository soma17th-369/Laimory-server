package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * fake AI 디스패처의 콜백 HTTP(URL·헤더·3필드 바디)와 예외 미전파, 토큰 미로깅을 검증한다. 인프라 0.
 *
 * <p>플레인 인스턴스라 @Async 미적용(동기 실행), delay는 ZERO 주입으로 테스트 지연 없음.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class FakeAiTimelineEventSuggestionDispatcherTest {

    private static final String TASK_ID = "task-1";
    private static final String SECRET_TOKEN = "SUPER-SECRET-CALLBACK-TOKEN-zzz";
    private static final String CALLBACK_URL = "http://localhost:8080/s/api/v1/timeline/drafts/task-1/callback";

    @Mock
    private FakeAiEventSuggestionStagingService fakeAiEventSuggestionStagingService;

    private MockRestServiceServer server;
    private FakeAiTimelineEventSuggestionDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        dispatcher = new FakeAiTimelineEventSuggestionDispatcher(
                fakeAiEventSuggestionStagingService, builder, Duration.ZERO);
    }

    @Test
    void dispatch_postsSuccessCallback_whenStaged(CapturedOutput output) {
        when(fakeAiEventSuggestionStagingService.stage(TASK_ID)).thenReturn(true);
        server.expect(requestTo(CALLBACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Callback-Token", SECRET_TOKEN))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.errorCode").value(nullValue()))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andRespond(withSuccess());

        dispatcher.dispatch(TASK_ID, SECRET_TOKEN);

        server.verify();
        assertThat(output).doesNotContain(SECRET_TOKEN);
        assertThat(output.getOut()).contains("fake AI callback sent");
    }

    @Test
    void dispatch_postsFailedCallback_whenNoSources(CapturedOutput output) {
        when(fakeAiEventSuggestionStagingService.stage(TASK_ID)).thenReturn(false);
        server.expect(requestTo(CALLBACK_URL))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("ERROR_1008"))
                .andExpect(jsonPath("$.error").value("no source items"))
                .andRespond(withSuccess());

        dispatcher.dispatch(TASK_ID, SECRET_TOKEN);

        server.verify();
        assertThat(output).doesNotContain(SECRET_TOKEN);
    }

    @Test
    void dispatch_postsFailedCallbackWithFixedMessage_whenStagingThrows(CapturedOutput output) {
        when(fakeAiEventSuggestionStagingService.stage(TASK_ID)).thenThrow(new IllegalStateException("db down"));
        server.expect(requestTo(CALLBACK_URL))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("ERROR_1008"))
                .andExpect(jsonPath("$.error").value("fake staging failed")) // 고정 문구 — 예외 메시지 미노출
                .andRespond(withSuccess());

        assertThatCode(() -> dispatcher.dispatch(TASK_ID, SECRET_TOKEN)).doesNotThrowAnyException();

        server.verify();
        assertThat(output).doesNotContain(SECRET_TOKEN);
        assertThat(output.getOut()).contains("fake AI staging failed");
    }

    @Test
    void dispatch_doesNotPropagate_whenCallbackHttpFails(CapturedOutput output) {
        when(fakeAiEventSuggestionStagingService.stage(TASK_ID)).thenReturn(true);
        server.expect(requestTo(CALLBACK_URL)).andRespond(withServerError());

        assertThatCode(() -> dispatcher.dispatch(TASK_ID, SECRET_TOKEN)).doesNotThrowAnyException();

        server.verify();
        assertThat(output).doesNotContain(SECRET_TOKEN);
        assertThat(output.getOut()).contains("fake AI callback failed");
    }
}
