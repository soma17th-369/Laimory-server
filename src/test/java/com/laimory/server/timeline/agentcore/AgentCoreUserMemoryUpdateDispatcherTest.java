package com.laimory.server.timeline.agentcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateRequest;
import com.laimory.server.timeline.service.TimelineAiDispatchRejectedException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.InvokeAgentRuntimeRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.InvokeAgentRuntimeResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.RuntimeClientErrorException;
import software.amazon.awssdk.services.bedrockagentcore.model.ValidationException;

/**
 * AgentCore User Memory 디스패처 계약 검증(#338). Timeline dispatcher와 같은 adapter를 쓰므로 분류
 * 매트릭스 전체는 {@link AgentCoreTimelineAiDispatcherTest}가 소유하고, 여기서는 <b>이 흐름 고유의
 * 계약</b>을 고정한다 — {@code requestType=USER_MEMORY_UPDATE}, 접수 body 불변, 흐름별 session id
 * prefix, 그리고 두 갈래 실패 분류가 이 dispatcher에서도 그대로인지.
 */
@ExtendWith(MockitoExtension.class)
class AgentCoreUserMemoryUpdateDispatcherTest {

    private static final String RUNTIME_ARN =
            "arn:aws:bedrock-agentcore:ap-northeast-2:123456789012:runtime/laimory_ai-AbCdEf";
    private static final String ENDPOINT = "DEFAULT";
    private static final String TASK_ID = "5f2b7c1a-9d3e-4a6b-8c0d-1e2f3a4b5c6d";
    private static final String TASK_TOKEN = "super-secret-task-token";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private static final ObjectMapper MAPPER = bootObjectMapper();

    @Mock
    private BedrockAgentCoreClient client;

    private AgentCoreUserMemoryUpdateDispatcher dispatcher;
    private ListAppender<ILoggingEvent> logs;
    private Logger dispatcherLogger;

    private static ObjectMapper bootObjectMapper() {
        AtomicReference<ObjectMapper> holder = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> holder.set(context.getBean(ObjectMapper.class)));
        return holder.get();
    }

    @BeforeEach
    void setUp() {
        dispatcher = new AgentCoreUserMemoryUpdateDispatcher(new AgentCoreDispatchClient(client,
                new AgentCoreProperties(RUNTIME_ARN, ENDPOINT, "ap-northeast-2"), MAPPER));
        logs = new ListAppender<>();
        logs.start();
        dispatcherLogger = (Logger) LoggerFactory.getLogger(AgentCoreUserMemoryUpdateDispatcher.class);
        dispatcherLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        dispatcherLogger.detachAppender(logs);
    }

    private static AiUserMemoryUpdateRequest request() {
        return new AiUserMemoryUpdateRequest(TASK_ID, TASK_TOKEN, null,
                List.of(new AiUserMemoryUpdateRequest.DailyTimeline(
                        LocalDate.of(2026, 8, 4), "Asia/Seoul", EmotionType.HAPPY,
                        List.of(new AiUserMemoryUpdateRequest.Event(
                                TimelineEventType.MEAL, "점심", null, "무엇을 드셨나요?",
                                OffsetDateTime.of(2026, 8, 4, 12, 10, 0, 0, KST),
                                OffsetDateTime.of(2026, 8, 4, 13, 0, 0, 0, KST),
                                "맛있었다")))));
    }

    private void respondWith(int statusCode, String body) {
        when(client.invokeAgentRuntime(any(InvokeAgentRuntimeRequest.class),
                ArgumentMatchers.<ResponseTransformer<InvokeAgentRuntimeResponse, Object>>any()))
                .thenAnswer(invocation -> {
                    ResponseTransformer<InvokeAgentRuntimeResponse, Object> transformer =
                            invocation.getArgument(1);
                    InvokeAgentRuntimeResponse response = InvokeAgentRuntimeResponse.builder()
                            .statusCode(statusCode)
                            .contentType("application/json")
                            .build();
                    return transformer.transform(response, AbortableInputStream.create(
                            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
                });
    }

    private void failWith(RuntimeException exception) {
        when(client.invokeAgentRuntime(any(InvokeAgentRuntimeRequest.class),
                ArgumentMatchers.<ResponseTransformer<InvokeAgentRuntimeResponse, Object>>any()))
                .thenThrow(exception);
    }

    private InvokeAgentRuntimeRequest capturedRequest() {
        ArgumentCaptor<InvokeAgentRuntimeRequest> captor =
                ArgumentCaptor.forClass(InvokeAgentRuntimeRequest.class);
        verify(client, times(1)).invokeAgentRuntime(captor.capture(),
                ArgumentMatchers.<ResponseTransformer<InvokeAgentRuntimeResponse, Object>>any());
        return captor.getValue();
    }

    @Test
    void dispatch_wrapsUserMemoryBodyWithoutChangingIt() throws Exception {
        respondWith(200, "{\"taskId\":\"%s\",\"status\":\"PROCESSING\"}".formatted(TASK_ID));

        dispatcher.dispatch(request());

        InvokeAgentRuntimeRequest sent = capturedRequest();
        assertThat(sent.agentRuntimeArn()).isEqualTo(RUNTIME_ARN);
        assertThat(sent.qualifier()).isEqualTo(ENDPOINT);
        assertThat(sent.contentType()).isEqualTo("application/json");
        assertThat(sent.accept()).isEqualTo("application/json");

        JsonNode wrapper = MAPPER.readTree(sent.payload().asByteArray());
        assertThat(wrapper.get("requestType").asText()).isEqualTo("USER_MEMORY_UPDATE");
        // payload는 HTTP mode가 보내는 접수 body와 정확히 같아야 한다(wrapper는 봉투일 뿐).
        assertThat(MAPPER.writeValueAsString(wrapper.get("payload")))
                .isEqualTo(MAPPER.writeValueAsString(request()));
    }

    @Test
    void dispatch_derivesRuntimeSessionIdFromTaskId() {
        respondWith(202, "{\"taskId\":\"%s\",\"status\":\"PROCESSING\"}".formatted(TASK_ID));

        assertThatCode(() -> dispatcher.dispatch(request())).doesNotThrowAnyException();

        String sessionId = capturedRequest().runtimeSessionId();
        assertThat(sessionId).isEqualTo(AgentCoreUserMemoryUpdateDispatcher.SESSION_ID_PREFIX + TASK_ID);
        assertThat(sessionId.length()).isBetween(33, 256);
        assertThat(logs.list).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain(TASK_TOKEN));
    }

    @Test
    void dispatch_taskIdMismatch_isUnknown() {
        respondWith(200, "{\"taskId\":\"other-task\",\"status\":\"PROCESSING\"}");

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class);
    }

    @Test
    void dispatch_preInvocationRejection_isRejected() {
        failWith(ValidationException.builder().statusCode(400).message("invalid").build());

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(TimelineAiDispatchRejectedException.class)
                .hasMessageNotContaining(TASK_TOKEN);
    }

    @Test
    void dispatch_runtimeClientError_isUnknown() {
        RuntimeClientErrorException error =
                RuntimeClientErrorException.builder().statusCode(424).message("runtime client").build();
        failWith(error);

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isSameAs(error)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class);
    }
}
