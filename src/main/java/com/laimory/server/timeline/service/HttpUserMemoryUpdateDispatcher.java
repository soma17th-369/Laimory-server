package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.AiTimelineDispatchResponse;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateRequest;
import java.time.Duration;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * 실 AI HTTP 디스패처({@code app.ai.mode=http}). {@code POST {base-url}/v1/user-memory}로 접수 body를 보내고
 * 202 + 동일 taskId + PROCESSING을 검증한다. 접수 응답 shape는 draft dispatch와 같다.
 *
 * <p>실패 분류는 {@link UserMemoryUpdateDispatcher} 계약대로다 — 4xx만
 * {@link TimelineAiDispatchRejectedException}(미접수 확정)이고 나머지는 UNKNOWN으로 전파한다.
 *
 * <p><b>read timeout은 반드시 유한해야 한다.</b> 이 dispatch는 worker 스레드에서 실행되므로 무한 대기는
 * 다른 사용자의 갱신 재시도까지 정지시킨다.
 *
 * <p>⚠️ {@code taskToken}은 비밀 — 어떤 로그에도 포함하지 않는다(접수 body로만 전송).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "http")
public class HttpUserMemoryUpdateDispatcher implements UserMemoryUpdateDispatcher {

    static final String DISPATCH_PATH = "/v1/user-memory";
    private static final String ACCEPTED_STATUS = "PROCESSING";

    private final RestClient restClient;

    // base-url·타임아웃 프로퍼티 주입과 request factory 구성이 있어 @RequiredArgsConstructor 대신 명시적 생성자.
    // base-url 검증은 draft dispatcher가 기동 시 이미 수행하므로 여기서 중복하지 않는다(같은 프로퍼티).
    public HttpUserMemoryUpdateDispatcher(
            RestClient.Builder restClientBuilder,
            @Value("${app.ai.http.base-url}") String baseUrl,
            @Value("${app.ai.http.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.ai.http.read-timeout:5s}") Duration readTimeout) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(connectTimeout)
                                .withReadTimeout(readTimeout)))
                .build();
    }

    @Override
    public void dispatch(AiUserMemoryUpdateRequest request) {
        ResponseEntity<AiTimelineDispatchResponse> response;
        try {
            response = restClient.post()
                    .uri(DISPATCH_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toEntity(AiTimelineDispatchResponse.class);
        } catch (HttpClientErrorException e) {
            // 4xx 수신 = AI가 HTTP 계층에서 거절(스키마 422 등). 접수·처리 없음이 확정 → 미접수 확정.
            throw new TimelineAiDispatchRejectedException(
                    "AI가 User Memory 갱신 접수를 거절했습니다(4xx): " + e.getStatusCode(), e);
        }
        // 그 외 예외(5xx, 전송/timeout)는 UNKNOWN이므로 catch하지 않고 그대로 전파한다.

        AiTimelineDispatchResponse body = response.getBody();
        if (response.getStatusCode() != HttpStatus.ACCEPTED || body == null
                || !Objects.equals(body.taskId(), request.taskId())
                || !ACCEPTED_STATUS.equals(body.status())) {
            // 응답은 받았지만 계약 불일치 = 접수 여부 불명(UNKNOWN) — 미접수로 확정하지 않는다.
            throw new IllegalStateException("AI 접수 계약 불일치: status=%s bodyTaskId=%s bodyStatus=%s"
                    .formatted(response.getStatusCode(),
                            body == null ? null : body.taskId(),
                            body == null ? null : body.status()));
        }
        log.info("user memory update dispatch accepted: taskId={}", request.taskId());
    }
}
