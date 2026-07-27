package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.dto.AiTimelineDispatchResponse;
import java.net.URI;
import java.net.URISyntaxException;
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
 * 실 AI HTTP 디스패처({@code app.ai.mode=http}). {@code POST {base-url}/v1/timeline}으로 접수 body를 보내고
 * 202 + 동일 taskId + PROCESSING을 검증한다.
 *
 * <p><b>실패 분류(중요)</b>: 결과가 "미접수 확정"인지 "접수 불명(UNKNOWN)"인지 구분해 서로 다른 예외로
 * 던진다. 호출부는 이 구분으로만 FAILED 확정 여부를 정한다.
 * <ul>
 *   <li><b>미접수 확정</b> → {@link TimelineAiDispatchRejectedException}: <b>4xx 응답 수신</b>(AI HTTP 계층이
 *       스키마 검증 등으로 요청을 거절 — 접수·background 처리 없음이 확정). 호출부가 FAILED로 종결해도 안전하다.</li>
 *   <li><b>UNKNOWN</b> → 그 외 모든 예외 그대로 전파(read timeout·connect 실패·5xx 서버 오류·비202/계약 불일치):
 *       AI가 이미 접수해 final write를 진행 중일 수 있으므로 호출부는 FAILED로 확정하지 않고
 *       PROCESSING을 유지한다(AI callback 또는 TTL 만료가 종결).</li>
 * </ul>
 * 5xx를 UNKNOWN으로 두는 이유: 서버 오류는 접수 이후 발생했을 수 있어 미접수를 확정하지 못한다(보수적).
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
    // AI 접수는 202 즉시 반환 계약이라 read timeout을 짧게 둔다. base-url은 http 모드 필수라 기동 시 검증한다
    // (빈 값·상대 URI면 컨텍스트를 못 뜨게 fail-fast — 첫 dispatch에서야 터지는 상황 방지).
    public HttpTimelineAiDispatcher(
            RestClient.Builder restClientBuilder,
            @Value("${app.ai.http.base-url}") String baseUrl,
            @Value("${app.ai.http.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.ai.http.read-timeout:5s}") Duration readTimeout) {
        requireAbsoluteHttpUrl(baseUrl);
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(connectTimeout)
                                .withReadTimeout(readTimeout)))
                .build();
    }

    /** http 모드 base-url이 non-blank absolute http(s) URL인지 기동 시 강제한다(아니면 IllegalStateException). */
    private static void requireAbsoluteHttpUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "app.ai.http.base-url은 http 모드에서 필수입니다(APP_AI_HTTP_BASE_URL 미주입).");
        }
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("app.ai.http.base-url이 유효한 URI가 아닙니다: " + baseUrl, e);
        }
        String scheme = uri.getScheme();
        boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!httpScheme || uri.getHost() == null) {
            throw new IllegalStateException(
                    "app.ai.http.base-url은 absolute http(s) URL이어야 합니다: " + baseUrl);
        }
    }

    @Override
    public void dispatch(AiTimelineDispatchRequest request) {
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
                    "AI가 접수를 거절했습니다(4xx): " + e.getStatusCode(), e);
        }
        // 그 외 예외(5xx=HttpServerErrorException, 전송/timeout=ResourceAccessException)는 UNKNOWN이므로
        // catch하지 않고 그대로 전파한다.

        AiTimelineDispatchResponse body = response.getBody();
        if (response.getStatusCode() != HttpStatus.ACCEPTED || body == null
                || !Objects.equals(body.taskId(), request.taskId())
                || !ACCEPTED_STATUS.equals(body.status())) {
            // 응답은 받았지만 202/taskId/PROCESSING 계약 불일치 = 접수 여부 불명(UNKNOWN) — 미접수로 확정하지 않는다.
            throw new IllegalStateException("AI 접수 계약 불일치: status=%s bodyTaskId=%s bodyStatus=%s"
                    .formatted(response.getStatusCode(),
                            body == null ? null : body.taskId(),
                            body == null ? null : body.status()));
        }
        log.info("timeline ai dispatch accepted: taskId={}", request.taskId());
    }
}
