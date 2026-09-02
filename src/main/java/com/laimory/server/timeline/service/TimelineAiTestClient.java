package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineTestInputRequest;
import com.laimory.server.timeline.dto.TimelineAiTestResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * AI 동기 테스트 endpoint({@code POST {ai-base}/v1/timeline/test}) 호출자. dev 전용이라
 * {@code app.ai.timeline-test.enabled=true}에서만 빈으로 등록되고, 그때는 URL·timeout이 잘못되면
 * 첫 호출이 아니라 기동에서 실패한다({@link HttpTimelineAiDispatcher}와 같은 생성자 검증 방식).
 *
 * <p>운영 dispatcher와 성격이 다르다 — 접수(202) 확인이 아니라 <b>추론이 끝날 때까지 동기로 기다린다</b>.
 * 그래서 read timeout이 AI {@code PIPELINE_TIMEOUT_SEC}보다 길어야 하고(기동 시 강제), 한 번 호출하면
 * <b>재시도하지 않는다</b>(같은 추론을 두 번 돌리면 토큰 비용이 두 배다).
 *
 * <p>요청·응답 모두 크기 상한을 갖는다. 요청은 직렬화 결과가 상한을 넘으면 전송 전에 400으로 끝내고,
 * 응답은 상한까지만 읽고 초과를 계약 위반으로 처리한다.
 *
 * <p>⚠️ AI 오류 body의 자유 text {@code error}는 읽지 않는다 — 사용자 원문이 섞일 수 있어 numeric
 * {@code errorCode}만 꺼낸다.
 */
@Component
@ConditionalOnProperty(name = "app.ai.timeline-test.enabled", havingValue = "true")
public class TimelineAiTestClient {

    /**
     * AI가 제한 시간 안에 <b>마지막 확정본</b>을 돌려줬다는 표시(실패가 아니다). AI와의 계약은 헤더지만
     * 우리 응답에서는 {@code timedOut} body 필드로 나간다 — 결과와 그 결과의 성격은 같은 자리에 있어야
     * 호출자가 헤더를 따로 보지 않고도 읽는다.
     */
    static final String TIMED_OUT_HEADER = "X-Timeline-Timed-Out";

    /**
     * AI 응답 상한. AI 결과는 자기 계약(Event 목록)으로 이미 bounded라 설정 knob을 두지 않는다
     * ({@code AgentCoreDispatchClient.MAX_ACK_BYTES}와 같은 판단).
     */
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    /** AI {@code PIPELINE_TIMEOUT_SEC} 기본값 — read timeout이 이보다 길어야 한다. */
    static final Duration AI_PIPELINE_TIMEOUT = Duration.ofSeconds(120);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final int maxRequestBytes;

    // URL·timeout 검증과 request factory 구성이 있어 @RequiredArgsConstructor 대신 명시적 생성자
    // (HttpTimelineAiDispatcher 선례). ObjectMapper는 Boot가 구성한 것이어야 한다 — 직접 만든 mapper는
    // JavaTimeModule이 없어 recordDate·시각 포맷이 AI 계약과 달라진다.
    TimelineAiTestClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                         @Value("${app.ai.timeline-test.url:}") String url,
                         @Value("${app.ai.timeline-test.connect-timeout:3s}") Duration connectTimeout,
                         @Value("${app.ai.timeline-test.read-timeout:150s}") Duration readTimeout,
                         @Value("${app.ai.timeline-test.max-request-bytes:1MB}") DataSize maxRequestBytes) {
        this.objectMapper = objectMapper;
        this.endpoint = requireAbsoluteHttpUrl(url);
        this.maxRequestBytes = requireRequestCap(maxRequestBytes);
        requirePositive(connectTimeout, "connect-timeout");
        requireReadTimeoutOutlastsAiPipeline(readTimeout);
        this.restClient = restClientBuilder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(connectTimeout)
                                .withReadTimeout(readTimeout)))
                .build();
    }

    /**
     * AI에 추론을 요청하고 결과를 그대로 돌려준다. {@code taskId}는 요청에 실린 값을 그대로 되싣는다 —
     * 호출자 응답과 AI 로그가 같은 상관키를 갖게 하기 위해서다.
     *
     * @throws IllegalArgumentException 직렬화 결과가 요청 상한을 넘음(호출자 입력 문제 → 400)
     * @throws TimelineAiTestCallException AI 4xx/5xx·timeout·전송 실패·비 JSON·계약 불일치·응답 상한 초과
     */
    public TimelineAiTestResponse generate(AiTimelineTestInputRequest input) {
        byte[] payload = serialize(input);
        try {
            return restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((clientRequest, clientResponse) -> readResponse(input.taskId(), clientResponse));
        } catch (RestClientException e) {
            // read timeout·connect 실패·전송 오류 — AI 응답 자체가 없어 status·errorCode를 알 수 없다.
            throw new TimelineAiTestCallException(
                    "AI 동기 테스트 호출 실패: " + e.getClass().getSimpleName(), null, null);
        }
    }

    /**
     * 전송 body를 만들고 상한을 넘는지 본다. 상한 초과는 호출자가 보낸 입력이 그만큼 컸다는 뜻이라
     * 400으로 끝낸다 — AI를 부르지 않으므로 토큰 비용도 발생하지 않는다.
     */
    private byte[] serialize(AiTimelineTestInputRequest input) {
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(input);
        } catch (IOException e) {
            // Jackson 예외 메시지는 payload 원문을 포함할 수 있어 cause로도 연결하지 않는다.
            throw new IllegalArgumentException("AI 동기 테스트 요청을 직렬화하지 못했습니다.");
        }
        if (payload.length > maxRequestBytes) {
            throw new IllegalArgumentException(
                    "AI 동기 테스트 요청이 상한(" + maxRequestBytes + " byte)을 초과했습니다.");
        }
        return payload;
    }

    /** 응답을 상한까지만 읽고 status·계약을 검증한다. 초과분은 읽지 않고 계약 위반으로 끝낸다. */
    private TimelineAiTestResponse readResponse(String taskId, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
        boolean truncated = body.length > MAX_RESPONSE_BYTES;

        if (!status.is2xxSuccessful()) {
            throw new TimelineAiTestCallException("AI가 오류를 반환했습니다.",
                    status.value(), truncated ? null : aiErrorCode(body));
        }
        if (truncated) {
            throw new TimelineAiTestCallException(
                    "AI 응답이 상한(" + MAX_RESPONSE_BYTES + " byte)을 초과했습니다.", status.value(), null);
        }
        AiTimelineResultRequest result = parse(body, status);
        requireResultContract(result, status);
        boolean timedOut = "true".equalsIgnoreCase(response.getHeaders().getFirst(TIMED_OUT_HEADER));
        return new TimelineAiTestResponse(taskId, timedOut, result.events());
    }

    private AiTimelineResultRequest parse(byte[] body, HttpStatusCode status) {
        try {
            AiTimelineResultRequest result = objectMapper.readValue(body, AiTimelineResultRequest.class);
            if (result == null) {
                throw new TimelineAiTestCallException("AI 응답 body가 비어 있습니다.", status.value(), null);
            }
            return result;
        } catch (IOException e) {
            // 파싱 실패 메시지에는 body 조각이 실릴 수 있어 문구를 고정한다.
            throw new TimelineAiTestCallException("AI 응답이 JSON 계약과 맞지 않습니다.", status.value(), null);
        }
    }

    /**
     * 운영 결과 저장이 요구하는 최소 계약만 확인한다 — 여기서 저장하지는 않지만, 계약을 어긴 결과를
     * 200으로 돌려주면 이 endpoint의 존재 이유(contract 검증)가 사라진다. 위반 문구에 Event 텍스트를
     * 넣지 않고 index만 남긴다.
     */
    private static void requireResultContract(AiTimelineResultRequest result, HttpStatusCode status) {
        List<AiTimelineResultRequest.Event> events = result.events();
        if (events == null || events.isEmpty()) {
            throw new TimelineAiTestCallException("AI 응답에 event가 없습니다.", status.value(), null);
        }
        for (int index = 0; index < events.size(); index++) {
            AiTimelineResultRequest.Event event = events.get(index);
            boolean valid = event != null
                    && event.eventType() != null
                    && event.title() != null && !event.title().isBlank()
                    && event.startAt() != null
                    && event.sourceRawIds() != null && !event.sourceRawIds().isEmpty();
            if (!valid) {
                throw new TimelineAiTestCallException(
                        "AI 응답 event 계약 위반: index=" + index, status.value(), null);
            }
        }
    }

    /** 오류 body에서 numeric {@code errorCode}만 꺼낸다. 자유 text {@code error}는 읽지 않는다. */
    private Integer aiErrorCode(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode code = root == null ? null : root.get("errorCode");
            return code != null && code.isIntegralNumber() && code.canConvertToInt() ? code.intValue() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static URI requireAbsoluteHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "app.ai.timeline-test.url은 테스트 endpoint 활성화 시 필수입니다"
                            + "(APP_AI_TIMELINE_TEST_URL 미주입).");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("app.ai.timeline-test.url이 유효한 URI가 아닙니다.", e);
        }
        String scheme = uri.getScheme();
        boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!httpScheme || uri.getHost() == null) {
            throw new IllegalStateException("app.ai.timeline-test.url은 absolute http(s) URL이어야 합니다.");
        }
        return uri;
    }

    /**
     * AI는 제한 시간이 끝나면 마지막 확정본을 {@code X-Timeline-Timed-Out}과 함께 <b>정상 200</b>으로
     * 돌려준다. read timeout이 그보다 짧으면 성공 응답을 받기 직전에 끊어 502로 만들므로 기동 시 막는다.
     */
    private static void requireReadTimeoutOutlastsAiPipeline(Duration readTimeout) {
        requirePositive(readTimeout, "read-timeout");
        if (readTimeout.compareTo(AI_PIPELINE_TIMEOUT) <= 0) {
            throw new IllegalStateException(
                    "app.ai.timeline-test.read-timeout(" + readTimeout + ")은 AI PIPELINE_TIMEOUT_SEC("
                            + AI_PIPELINE_TIMEOUT + ")보다 길어야 합니다 — 같거나 짧으면 AI가 "
                            + "X-Timeline-Timed-Out과 함께 돌려주는 정상 응답을 끊게 됩니다.");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("app.ai.timeline-test." + name + "은(는) 양수여야 합니다.");
        }
    }

    /** {@code readNBytes(cap + 1)} 오버플로를 막는 상한. 실제 body는 이보다 훨씬 작다. */
    private static int requireRequestCap(DataSize value) {
        long bytes = value == null ? 0 : value.toBytes();
        if (bytes <= 0 || bytes > 64L * 1024 * 1024) {
            throw new IllegalStateException(
                    "app.ai.timeline-test.max-request-bytes는 1B 이상 64MB 이하여야 합니다.");
        }
        return (int) bytes;
    }
}
