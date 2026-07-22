package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.dto.AiTimelineDispatchResponse;
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
import org.springframework.web.client.RestClient;

/**
 * 실 AI HTTP 디스패처({@code app.ai.mode=http}). {@code POST {base-url}/v1/timeline}으로 접수 body를 보내고
 * 202 + 동일 taskId + PROCESSING을 검증한다 — 셋 중 하나라도 어긋나면 던져서 호출부가 task를 FAILED로
 * 종결한다(4xx/5xx는 RestClient 기본 예외, 타임아웃은 request factory 설정).
 *
 * <p>현재 AI endpoint는 무인증이다(private network 전제) — production 전 service authentication을 추가할 때
 * request header와 contract fixture를 양 저장소에서 함께 갱신한다.
 *
 * <p>⚠️ {@code callbackToken}은 비밀 — 어떤 로그에도 포함하지 않는다(접수 body로만 전송).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "http")
public class HttpTimelineAiDispatcher implements TimelineAiDispatcher {

    static final String DISPATCH_PATH = "/v1/timeline";
    private static final String ACCEPTED_STATUS = "PROCESSING";

    private final RestClient restClient;

    // base-url·타임아웃 프로퍼티 주입과 request factory 구성이 있어 @RequiredArgsConstructor 대신 명시적 생성자.
    // AI 접수는 202 즉시 반환 계약이라 read timeout을 짧게 둔다 — AI가 응답 없이 매달리면 요청 스레드를
    // 잡아두는 대신 예외 → FAILED 종결이 낫다(테스트는 MockWebServer 실 HTTP 루프백 사용).
    public HttpTimelineAiDispatcher(
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
    public void dispatch(AiTimelineDispatchRequest request) {
        ResponseEntity<AiTimelineDispatchResponse> response = restClient.post()
                .uri(DISPATCH_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(AiTimelineDispatchResponse.class);

        AiTimelineDispatchResponse body = response.getBody();
        if (response.getStatusCode() != HttpStatus.ACCEPTED || body == null
                || !Objects.equals(body.taskId(), request.taskId())
                || !ACCEPTED_STATUS.equals(body.status())) {
            throw new IllegalStateException("AI 접수 계약 불일치: status=%s bodyTaskId=%s bodyStatus=%s"
                    .formatted(response.getStatusCode(),
                            body == null ? null : body.taskId(),
                            body == null ? null : body.status()));
        }
        log.info("timeline ai dispatch accepted: taskId={}", request.taskId());
    }
}
