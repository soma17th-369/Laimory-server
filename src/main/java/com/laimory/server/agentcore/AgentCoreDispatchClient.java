package com.laimory.server.agentcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.agentcore.AgentCoreDispatchRequest;
import com.laimory.server.timeline.dto.AiTimelineDispatchResponse;
import com.laimory.server.timeline.service.TimelineAiDispatchRejectedException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.ConflictException;
import software.amazon.awssdk.services.bedrockagentcore.model.InternalServerException;
import software.amazon.awssdk.services.bedrockagentcore.model.InvokeAgentRuntimeRequest;
import software.amazon.awssdk.services.bedrockagentcore.model.InvokeAgentRuntimeResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.RetryableConflictException;
import software.amazon.awssdk.services.bedrockagentcore.model.RuntimeClientErrorException;
import software.amazon.awssdk.services.bedrockagentcore.model.ServiceException;

/**
 * AgentCore 접수 공통 adapter({@code app.ai.mode=agentcore} — #338). Timeline·User Memory 두 dispatcher가
 * 공유하는 AWS 호출·응답 파싱·실패 분류를 소유한다(도메인 dispatcher 경계는 그대로 유지).
 *
 * <p>공통 wrapper({@link AgentCoreDispatchRequest})를 JSON으로 직렬화해
 * {@code InvokeAgentRuntime}의 payload로 보내고, ack가 요청과 같은 {@code taskId} +
 * {@code PROCESSING}인지 HTTP dispatcher와 동일하게 검증한다.
 *
 * <p><b>실패 분류(중요)</b> — 호출부는 이 구분으로만 FAILED 확정 여부를 정한다.
 * <ul>
 *   <li><b>미접수 확정</b> → {@link TimelineAiDispatchRejectedException}: 전송 전 자체 검증 실패
 *       (session id·직렬화)와 <b>runtime에 도달하기 전에 서비스가 거절한 4xx</b>
 *       (ValidationException·AccessDenied·ResourceNotFound·Throttling·ServiceQuotaExceeded 등),
 *       그리고 AI runtime이 4xx로 돌려준 ack. 접수·background 처리 없음이 확정이다.</li>
 *   <li><b>UNKNOWN</b> → 그 외 예외를 그대로 전파: {@link RetryableConflictException}(도달 여부 불명),
 *       {@link RuntimeClientErrorException}(runtime client 오류 — 접수 후 실패 가능),
 *       {@link InternalServerException}·5xx, {@code SdkClientException}(timeout·전송 실패),
 *       ack 계약 불일치. 호출부는 PROCESSING을 유지한다.</li>
 * </ul>
 *
 * <p>응답 스트림은 {@code ResponseTransformer}로 <b>최대 {@value #MAX_ACK_BYTES} byte까지만</b> 읽고
 * 초과분은 abort한다(ack는 수십 byte). SDK가 transform 종료 후 스트림을 닫으므로 누수가 없다.
 *
 * <p>⚠️ wrapper·payload·{@code taskToken}·응답 body는 로그·예외 메시지에 담지 않는다 — 예외에는
 * {@code statusCode}·{@code taskId}처럼 비밀이 아닌 항목만 남긴다.
 */
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "agentcore")
@RequiredArgsConstructor
public class AgentCoreDispatchClient {

    static final String CONTENT_TYPE = "application/json";
    static final int MAX_ACK_BYTES = 8 * 1024;
    private static final String ACCEPTED_STATUS = "PROCESSING";
    /** AgentCore runtime session id 계약(요청 33~256자). */
    private static final int SESSION_ID_MIN_LENGTH = 33;
    private static final int SESSION_ID_MAX_LENGTH = 256;
    private static final Pattern SESSION_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]*$");

    /**
     * 접수 여부를 확정할 수 없는 AWS 예외 — 4xx라도 runtime 도달 이후일 수 있어 UNKNOWN으로 둔다.
     * 나머지는 statusCode로 분류한다(SDK 버전이 예외 타입을 늘려도 계약이 흔들리지 않게).
     */
    private static final List<Class<? extends AwsServiceException>> UNKNOWN_OUTCOME_EXCEPTIONS = List.of(
            RetryableConflictException.class, RuntimeClientErrorException.class, ConflictException.class,
            InternalServerException.class, ServiceException.class);

    private final BedrockAgentCoreClient client;
    private final AgentCoreProperties properties;
    // Spring Boot가 구성한 ObjectMapper여야 한다 — 직접 만든 mapper는 JavaTimeModule이 없어 접수 body의
    // OffsetDateTime 포맷이 계약과 달라진다.
    private final ObjectMapper objectMapper;

    /**
     * wrapper를 AgentCore Runtime으로 접수시키고 ack를 검증한다.
     *
     * @param sessionIdPrefix 흐름 구분 prefix — {@code prefix + taskId}가 runtime session id가 된다
     *                        (taskId는 난수 UUID라 사용자 원문·token이 섞이지 않는다)
     */
    void dispatch(AgentCoreDispatchRequest<?> wrapper, String taskId, String sessionIdPrefix) {
        String sessionId = requireValidSessionId(sessionIdPrefix + taskId);
        InvokeAgentRuntimeRequest request = InvokeAgentRuntimeRequest.builder()
                .agentRuntimeArn(properties.runtimeArn())
                .qualifier(properties.endpoint())
                .runtimeSessionId(sessionId)
                .contentType(CONTENT_TYPE)
                .accept(CONTENT_TYPE)
                .payload(serialize(wrapper, taskId))
                .build();

        Ack ack;
        try {
            ack = client.invokeAgentRuntime(request, AgentCoreDispatchClient::readAck);
        } catch (AwsServiceException e) {
            throw classify(e, taskId);
        }
        // SdkClientException(timeout·전송 실패)과 transform 중 IOException은 UNKNOWN이라 catch하지 않는다.
        verifyAck(ack, taskId);
    }

    private SdkBytes serialize(AgentCoreDispatchRequest<?> wrapper, String taskId) {
        try {
            return SdkBytes.fromByteArray(objectMapper.writeValueAsBytes(wrapper));
        } catch (IOException e) {
            // 전송 자체를 하지 않았으므로 미접수 확정이다. Jackson 예외 메시지는 payload 원문을 포함할 수
            // 있어 cause로도 연결하지 않는다(taskToken 노출 방지).
            throw new TimelineAiDispatchRejectedException(
                    "AgentCore 접수 body 직렬화에 실패했습니다: taskId=" + taskId, null);
        }
    }

    /** 응답 body를 상한까지만 읽고 초과분은 abort한다(SDK가 transform 후 스트림을 닫는다). */
    private static Ack readAck(InvokeAgentRuntimeResponse response, AbortableInputStream stream)
            throws IOException {
        byte[] body = stream.readNBytes(MAX_ACK_BYTES + 1);
        boolean truncated = body.length > MAX_ACK_BYTES;
        if (truncated) {
            stream.abort();
        }
        return new Ack(response.statusCode(), body, truncated);
    }

    private RuntimeException classify(AwsServiceException e, String taskId) {
        if (UNKNOWN_OUTCOME_EXCEPTIONS.stream().anyMatch(type -> type.isInstance(e))) {
            return e;
        }
        int statusCode = e.statusCode();
        if (statusCode >= 400 && statusCode < 500) {
            // 서비스가 runtime 호출 전에 거절했다(요청 검증·권한·대상 부재·throttling) → 접수 없음이 확정.
            return new TimelineAiDispatchRejectedException(
                    "AgentCore가 접수를 거절했습니다(%d %s): taskId=%s"
                            .formatted(statusCode, e.getClass().getSimpleName(), taskId), e);
        }
        return e;
    }

    private void verifyAck(Ack ack, String taskId) {
        Integer statusCode = ack.statusCode();
        if (statusCode == null || statusCode < 200 || statusCode >= 300) {
            if (statusCode != null && statusCode >= 400 && statusCode < 500) {
                // AI runtime이 스키마 등으로 거절 — HTTP mode의 4xx와 같은 의미다.
                throw new TimelineAiDispatchRejectedException(
                        "AI가 접수를 거절했습니다(AgentCore statusCode=%d): taskId=%s"
                                .formatted(statusCode, taskId), null);
            }
            throw new IllegalStateException(
                    "AI 접수 계약 불일치: agentCoreStatusCode=%s taskId=%s".formatted(statusCode, taskId));
        }
        if (ack.truncated()) {
            throw new IllegalStateException(
                    "AI 접수 응답이 상한(%d byte)을 초과했습니다: taskId=%s".formatted(MAX_ACK_BYTES, taskId));
        }
        AiTimelineDispatchResponse body = parseAck(ack, taskId);
        if (body == null || !Objects.equals(body.taskId(), taskId)
                || !ACCEPTED_STATUS.equals(body.status())) {
            // 응답은 받았지만 taskId/PROCESSING 계약 불일치 = 접수 여부 불명(UNKNOWN) — 미접수로 확정하지 않는다.
            throw new IllegalStateException("AI 접수 계약 불일치: bodyTaskId=%s bodyStatus=%s taskId=%s"
                    .formatted(body == null ? null : body.taskId(),
                            body == null ? null : body.status(), taskId));
        }
    }

    private AiTimelineDispatchResponse parseAck(Ack ack, String taskId) {
        try {
            return objectMapper.readValue(ack.body(), AiTimelineDispatchResponse.class);
        } catch (IOException e) {
            // 파싱 실패는 접수 여부 불명이다. 예외 메시지에 응답 원문 조각이 실릴 수 있어 cause를 연결하지 않는다.
            throw new IllegalStateException("AI 접수 응답을 해석할 수 없습니다: taskId=" + taskId);
        }
    }

    /**
     * session id는 AgentCore 계약(33~256자)을 만족해야 한다. 위반은 우리 쪽 조립 오류이고 호출 자체를
     * 하지 않으므로 미접수 확정으로 분류한다.
     */
    private static String requireValidSessionId(String sessionId) {
        boolean valid = sessionId.length() >= SESSION_ID_MIN_LENGTH
                && sessionId.length() <= SESSION_ID_MAX_LENGTH
                && SESSION_ID.matcher(sessionId).matches();
        if (!valid) {
            throw new TimelineAiDispatchRejectedException(
                    "AgentCore runtime session id 계약(33~256자) 위반: length=" + sessionId.length(), null);
        }
        return sessionId;
    }

    /** 상한까지만 읽은 ack 응답(statusCode + body). */
    private record Ack(Integer statusCode, byte[] body, boolean truncated) {
    }
}
