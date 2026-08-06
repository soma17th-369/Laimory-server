package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateRequest;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateResultRequest;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * dev 전용 fake AI 디스패처. delay(추론 시간 흉내) 후 <b>자기 서버의 결과 endpoint를 실제 HTTP로</b>
 * 호출해, 앱이 dev 서버에서 저장 → 갱신 접수 → 결과 저장 흐름을 실제로 태워볼 수 있게 한다.
 *
 * <p>실 AI 계약과의 의도적 차이: <b>재시도 없음</b>(dev 도구). HTTP가 실패하면 task는 TTL로 소멸하고
 * User Memory는 원본 그대로 남는다. 서버가 8080이 아닌 포트로 떠 있으면 호출이 유실된다(고정 URL 전제).
 *
 * <p>inference가 없으므로 스키마 필수 필드만 채운 결정적 stub 문서를 만든다 — 값의 내용은 무의미하고
 * 왕복이 실제로 되는지만 확인한다.
 *
 * <p>⚠️ task token은 비밀 — 어떤 로그에도 포함하지 않는다(헤더로만 전송).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "fake")
public class FakeUserMemoryUpdateDispatcher implements UserMemoryUpdateDispatcher {

    static final String FAKE_SCHEMA_VERSION = "1.0";
    static final String FAKE_CURRENT_FOCUS = "[FAKE] dev 확인용 stub 문서";

    // 서버간 경로는 UserMemoryUpdateController 매핑의 복제다(서비스가 컨트롤러 상수를 참조하는 레이어
    // 역류를 피함). FakeTimelineAiDispatcher와 같은 방식이다.
    private static final String RESULT_URL_TEMPLATE =
            "http://localhost:8080" + ApiUrls.SERVER_API_URL.replace(ApiUrls.VERSION, "v1")
                    + "/user-memory/updates/{taskId}/result";
    private static final String TASK_TOKEN_HEADER = "Task-Token";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration resultDelay;

    // RestClient build + delay 프로퍼티 주입이 있어 @RequiredArgsConstructor 대신 명시적 생성자를 쓴다.
    // requestFactory는 커스텀하지 않는다 — 단위 테스트의 MockRestServiceServer.bindTo(builder)가 심는
    // mock factory를 덮어버리기 때문(dev 전용이라 기본 타임아웃 수용).
    public FakeUserMemoryUpdateDispatcher(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${app.ai.fake.callback-delay:2s}") Duration resultDelay) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.resultDelay = resultDelay;
    }

    @Async
    @Override
    public void dispatch(AiUserMemoryUpdateRequest request) {
        try {
            Thread.sleep(resultDelay.toMillis());
        } catch (InterruptedException e) {
            // 셧다운 시그널 — 아직 아무것도 저장하지 않았으므로 찌꺼기가 없다(task는 TTL로 소멸).
            Thread.currentThread().interrupt();
            return;
        }

        String taskId = request.taskId();
        try {
            restClient.post()
                    // URI template을 보존해야 Micrometer의 low-cardinality uri tag에 taskId 원문이 들어가지 않는다.
                    .uri(RESULT_URL_TEMPLATE, taskId)
                    .header(TASK_TOKEN_HEADER, request.taskToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiUserMemoryUpdateResultRequest(
                            AiUserMemoryUpdateResultRequest.STATUS_SUCCESS, stubMemory(), null, null))
                    .retrieve()
                    .toBodilessEntity();
            log.info("fake user memory update result sent: taskId={}", taskId);
        } catch (RuntimeException e) {
            log.warn("fake user memory update result failed: taskId={}", taskId, e);
        }
    }

    /** 스키마 필수 필드만 채운 결정적 stub. 서버는 문서를 해석하지 않으므로 왕복 확인용으로 충분하다. */
    private ObjectNode stubMemory() {
        ObjectNode memory = objectMapper.createObjectNode();
        memory.put("schemaVersion", FAKE_SCHEMA_VERSION);
        memory.put("updatedAt", clock.instant().toString());
        memory.put("currentFocus", FAKE_CURRENT_FOCUS);
        return memory;
    }
}
