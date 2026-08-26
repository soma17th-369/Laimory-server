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
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.service.TimelineAiDispatchRejectedException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockagentcore.model.InternalServerException;
import software.amazon.awssdk.services.bedrockagentcore.model.InvokeAgentRuntimeRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.InvokeAgentRuntimeResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockagentcore.model.RetryableConflictException;
import software.amazon.awssdk.services.bedrockagentcore.model.RuntimeClientErrorException;
import software.amazon.awssdk.services.bedrockagentcore.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockagentcore.model.ValidationException;

/**
 * AgentCore 타임라인 디스패처 계약 검증(#338). {@link BedrockAgentCoreClient}를 모킹해
 * {@code InvokeAgentRuntime} 요청 변환과 ack 검증, 그리고 <b>실패 분류</b>를 고정한다 — 인프라 0.
 *
 * <p>실패 분류가 이 테스트의 핵심이다: <b>runtime 도달 전 거절과 AI runtime의 4xx ack만</b> 미접수 확정
 * ({@link TimelineAiDispatchRejectedException})이고, 도달 여부가 불명인 오류(RetryableConflict·
 * RuntimeClientError·5xx·전송 실패·ack 계약 불일치)는 그 타입이 <b>아닌</b> 예외로 전파돼야 한다
 * (호출부가 PROCESSING task를 FAILED로 덮지 않도록).
 */
@ExtendWith(MockitoExtension.class)
class AgentCoreTimelineAiDispatcherTest {

    private static final String RUNTIME_ARN =
            "arn:aws:bedrock-agentcore:ap-northeast-2:123456789012:runtime/laimory_ai-AbCdEf";
    private static final String ENDPOINT = "DEFAULT";
    private static final String TASK_ID = "0198a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
    private static final String TASK_TOKEN = "super-secret-task-token";

    private static final ObjectMapper MAPPER = bootObjectMapper();

    @Mock
    private BedrockAgentCoreClient client;

    private AgentCoreTimelineAiDispatcher dispatcher;
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
        dispatcher = new AgentCoreTimelineAiDispatcher(new AgentCoreDispatchClient(client,
                new AgentCoreProperties(RUNTIME_ARN, ENDPOINT, "ap-northeast-2"), MAPPER));
        logs = new ListAppender<>();
        logs.start();
        dispatcherLogger = (Logger) LoggerFactory.getLogger(AgentCoreTimelineAiDispatcher.class);
        dispatcherLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        dispatcherLogger.detachAppender(logs);
    }

    private static AiTimelineDispatchRequest request() {
        ZoneOffset kst = ZoneOffset.ofHours(9);
        return new AiTimelineDispatchRequest(TASK_ID, TASK_TOKEN, 42L,
                new AiTimelineDispatchRequest.Window(
                        OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, kst),
                        OffsetDateTime.of(2026, 7, 23, 0, 0, 0, 0, kst)));
    }

    private static String ackBody(String taskId, String status) {
        return "{\"taskId\":\"%s\",\"status\":\"%s\"}".formatted(taskId, status);
    }

    private void respondWith(int statusCode, String body) {
        respondWith(statusCode, body.getBytes(StandardCharsets.UTF_8), new AtomicBoolean());
    }

    /** transformer를 실제로 구동해 production의 응답 소비 경로(유계 읽기·abort)를 그대로 태운다. */
    private void respondWith(int statusCode, byte[] body, AtomicBoolean aborted) {
        when(client.invokeAgentRuntime(any(InvokeAgentRuntimeRequest.class),
                ArgumentMatchers.<ResponseTransformer<InvokeAgentRuntimeResponse, Object>>any()))
                .thenAnswer(invocation -> {
                    ResponseTransformer<InvokeAgentRuntimeResponse, Object> transformer =
                            invocation.getArgument(1);
                    InvokeAgentRuntimeResponse response = InvokeAgentRuntimeResponse.builder()
                            .statusCode(statusCode)
                            .contentType("application/json")
                            .build();
                    AbortableInputStream stream = AbortableInputStream.create(
                            new ByteArrayInputStream(body), () -> aborted.set(true));
                    return transformer.transform(response, stream);
                });
    }

    private InvokeAgentRuntimeRequest capturedRequest() {
        ArgumentCaptor<InvokeAgentRuntimeRequest> captor =
                ArgumentCaptor.forClass(InvokeAgentRuntimeRequest.class);
        verify(client, times(1)).invokeAgentRuntime(captor.capture(),
                ArgumentMatchers.<ResponseTransformer<InvokeAgentRuntimeResponse, Object>>any());
        return captor.getValue();
    }

    // --- 요청 변환 ---

    @Test
    void dispatch_sendsWrapperToConfiguredRuntimeAndEndpoint() throws Exception {
        respondWith(200, ackBody(TASK_ID, "PROCESSING"));

        dispatcher.dispatch(request());

        InvokeAgentRuntimeRequest sent = capturedRequest();
        assertThat(sent.agentRuntimeArn()).isEqualTo(RUNTIME_ARN);
        assertThat(sent.qualifier()).isEqualTo(ENDPOINT);
        assertThat(sent.contentType()).isEqualTo("application/json");
        assertThat(sent.accept()).isEqualTo("application/json");

        JsonNode payload = MAPPER.readTree(sent.payload().asByteArray());
        assertThat(payload.get("requestType").asText()).isEqualTo("TIMELINE");
        assertThat(payload.get("payload").get("taskId").asText()).isEqualTo(TASK_ID);
        assertThat(payload.get("payload").get("window").get("startAt").asText())
                .isEqualTo("2026-07-22T00:00:00+09:00");
    }

    @Test
    void dispatch_derivesRuntimeSessionIdFromTaskId() {
        respondWith(200, ackBody(TASK_ID, "PROCESSING"));

        dispatcher.dispatch(request());

        String sessionId = capturedRequest().runtimeSessionId();
        assertThat(sessionId).isEqualTo(AgentCoreTimelineAiDispatcher.SESSION_ID_PREFIX + TASK_ID);
        // AgentCore 계약: 요청 session id는 33~256자.
        assertThat(sessionId.length()).isBetween(33, 256);
        assertThat(sessionId).doesNotContain(TASK_TOKEN);
    }

    @Test
    void dispatch_acceptedAck_doesNotThrowAndDoesNotLogToken() {
        respondWith(202, ackBody(TASK_ID, "PROCESSING"));

        assertThatCode(() -> dispatcher.dispatch(request())).doesNotThrowAnyException();

        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain(TASK_TOKEN));
        assertThat(logs.list.getLast().getFormattedMessage()).contains(TASK_ID);
    }

    // --- ack 계약 불일치 = UNKNOWN ---

    @Test
    void dispatch_taskIdMismatch_isUnknown() {
        respondWith(200, ackBody("other-task", "PROCESSING"));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class);
    }

    @Test
    void dispatch_nonProcessingStatus_isUnknown() {
        respondWith(200, ackBody(TASK_ID, "FAILED"));

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class);
    }

    @Test
    void dispatch_unparsableAck_isUnknownAndKeepsBodyOutOfMessage() {
        respondWith(200, "not-json");

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class)
                .hasMessageNotContaining("not-json");
    }

    @Test
    void dispatch_oversizedAck_isUnknownAndAbortsStream() {
        AtomicBoolean aborted = new AtomicBoolean();
        byte[] huge = new byte[AgentCoreDispatchClient.MAX_ACK_BYTES + 64];
        java.util.Arrays.fill(huge, (byte) 'x');
        respondWith(200, huge, aborted);

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class);
        assertThat(aborted).isTrue();
    }

    @Test
    void dispatch_runtimeServerErrorStatus_isUnknown() {
        respondWith(500, "{}");

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TimelineAiDispatchRejectedException.class);
    }

    // --- 미접수 확정 ---

    @Test
    void dispatch_runtimeClientErrorStatus_isRejected() {
        // AI runtime이 4xx로 돌려준 ack = 스키마 등으로 거절(HTTP mode의 4xx와 같은 의미).
        respondWith(422, "{}");

        assertThatThrownBy(() -> dispatcher.dispatch(request()))
                .isInstanceOf(TimelineAiDispatchRejectedException.class)
                .hasMessageContaining("422");
    }

    @Test
    void dispatch_serviceRejectionsBeforeInvocation_areRejected() {
        List<RuntimeException> preInvocationRejections = List.of(
                ValidationException.builder().statusCode(400).message("invalid").build(),
                AccessDeniedException.builder().statusCode(403).message("denied").build(),
                ResourceNotFoundException.builder().statusCode(404).message("missing").build(),
                ThrottlingException.builder().statusCode(429).message("throttled").build());

        for (RuntimeException rejection : preInvocationRejections) {
            AgentCoreTimelineAiDispatcher isolated = new AgentCoreTimelineAiDispatcher(
                    new AgentCoreDispatchClient(clientThrowing(rejection),
                            new AgentCoreProperties(RUNTIME_ARN, ENDPOINT, "ap-northeast-2"), MAPPER));

            assertThatThrownBy(() -> isolated.dispatch(request()))
                    .as(rejection.getClass().getSimpleName())
                    .isInstanceOf(TimelineAiDispatchRejectedException.class)
                    .hasMessageContaining(TASK_ID)
                    .hasMessageNotContaining(TASK_TOKEN);
        }
    }

    // --- 접수 불명 ---

    @Test
    void dispatch_ambiguousServiceErrors_areUnknown() {
        List<RuntimeException> ambiguous = List.of(
                RetryableConflictException.builder().statusCode(409).message("conflict").build(),
                RuntimeClientErrorException.builder().statusCode(424).message("runtime client").build(),
                InternalServerException.builder().statusCode(500).message("boom").build(),
                SdkClientException.create("read timeout"));

        for (RuntimeException error : ambiguous) {
            AgentCoreTimelineAiDispatcher isolated = new AgentCoreTimelineAiDispatcher(
                    new AgentCoreDispatchClient(clientThrowing(error),
                            new AgentCoreProperties(RUNTIME_ARN, ENDPOINT, "ap-northeast-2"), MAPPER));

            assertThatThrownBy(() -> isolated.dispatch(request()))
                    .as(error.getClass().getSimpleName())
                    .isNotInstanceOf(TimelineAiDispatchRejectedException.class)
                    .isSameAs(error);
        }
    }

    /** 분류 매트릭스용 일회성 client — MockitoExtension의 strict stubbing과 섞지 않는다. */
    private BedrockAgentCoreClient clientThrowing(RuntimeException exception) {
        BedrockAgentCoreClient throwing = org.mockito.Mockito.mock(BedrockAgentCoreClient.class);
        when(throwing.invokeAgentRuntime(any(InvokeAgentRuntimeRequest.class),
                ArgumentMatchers.<ResponseTransformer<InvokeAgentRuntimeResponse, Object>>any()))
                .thenThrow(exception);
        return throwing;
    }

    @Test
    void dispatch_callsRuntimeExactlyOnce() {
        // 접수는 비멱등이라 dispatcher는 스스로 재시도하지 않는다(SDK 재시도는 client 설정이 봉인).
        respondWith(200, ackBody(TASK_ID, "PROCESSING"));

        dispatcher.dispatch(request());

        capturedRequest();
    }
}
