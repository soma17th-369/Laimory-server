package com.laimory.server.timeline.service;

import com.laimory.server.common.ApiUrls;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * dev 전용 fake AI 디스패처. 실 AI 역할을 대행하는 dev runtime 시뮬레이터다 — 앱이 dev 서버에서
 * draft→입력 조회→결과 저장→콜백→SUCCESS 흐름을 실제로 태워볼 수 있도록, delay(추론 시간 흉내) 후
 * <b>실 AI와 같은 순서로 자기 서버의 서버간 endpoint 3개를 실제 HTTP로 호출</b>한다.
 *
 * <p>실 AI 계약과의 의도적 차이: <b>재시도 없음</b>(dev 도구). 어느 단계든 HTTP가 실패하면 task는
 * PROCESSING인 채 TTL로 소멸한다. 서버가 8080이 아닌 포트로 떠 있으면 호출이 유실된다(고정 URL 전제).
 * inference가 없으므로 분류는 {@code UNKNOWN} 고정이고 조회한 source 전부를 Event 하나로 묶는다.
 *
 * <p>⚠️ task token은 비밀 — 어떤 로그에도 포함하지 않는다(헤더로만 전송).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "fake")
public class FakeTimelineAiDispatcher implements TimelineAiDispatcher {

    static final String FAKE_TITLE = "[FAKE] 타임라인 이벤트 제안";

    // 서버간 경로는 TimelineAiTaskController/TimelineCallbackController 매핑의 복제다(서비스가 컨트롤러 상수를
    // 참조하는 레이어 역류를 피함). URL 형태는 FakeTimelineAiDispatcherTest, 컨트롤러 매핑은 각 컨트롤러
    // 테스트가 소유하며, 둘의 조합 드리프트를 자동 검출하는 테스트는 없다.
    private static final String SERVER_API_BASE =
            "http://localhost:8080" + ApiUrls.SERVER_API_URL.replace(ApiUrls.VERSION, "v1")
                    + "/timeline/drafts/{taskId}";
    private static final String INPUT_URL_TEMPLATE = SERVER_API_BASE + "/input";
    private static final String RESULT_URL_TEMPLATE = SERVER_API_BASE + "/result";
    private static final String CALLBACK_URL_TEMPLATE = SERVER_API_BASE + "/callback";
    private static final String TASK_TOKEN_HEADER = "Task-Token";

    private final RestClient restClient;
    private final Duration callbackDelay;

    // RestClient build + delay 프로퍼티 주입이 있어 @RequiredArgsConstructor 대신 명시적 생성자를 쓴다.
    // requestFactory는 커스텀하지 않는다 — 단위 테스트의 MockRestServiceServer.bindTo(builder)가 심는
    // mock factory를 덮어버리기 때문(dev 전용이라 기본 타임아웃 수용).
    public FakeTimelineAiDispatcher(
            RestClient.Builder restClientBuilder,
            @Value("${app.ai.fake.callback-delay:2s}") Duration callbackDelay) {
        this.restClient = restClientBuilder.build();
        this.callbackDelay = callbackDelay;
    }

    @Async
    @Override
    public void dispatch(AiTimelineDispatchRequest request) {
        // delay를 입력 조회 앞에 둔다: 앱이 PROCESSING을 관찰할 수 있고, 실 AI 동작(접수 → 추론 →
        // 저장·콜백)과 순서가 같다. 기본 2s는 단위 테스트에선 ZERO로 대체된다(생성자 주입).
        try {
            Thread.sleep(callbackDelay.toMillis());
        } catch (InterruptedException e) {
            // 셧다운 시그널 — 아직 아무것도 저장하지 않았으므로 찌꺼기가 없다(task는 PROCESSING TTL로 소멸).
            Thread.currentThread().interrupt();
            return;
        }

        String taskId = request.taskId();
        DraftTaskCallbackRequest result;
        try {
            AiTimelineTaskInputResponse input = getInput(taskId, request.callbackToken());
            postResult(taskId, request.callbackToken(), toResult(input));
            result = new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null, null);
        } catch (RuntimeException e) {
            log.warn("fake AI result flow failed: taskId={}", taskId, e);
            // 상세 예외는 위 로그에만 — 콜백 서비스가 error를 또 로깅하므로 고정 문구로 이중 노출을 피한다.
            result = new DraftTaskCallbackRequest(TaskStatus.FAILED, ExceptionType.AI_REPORTED_FAILURE.code(),
                    "fake result flow failed");
        }
        postCallback(taskId, request.callbackToken(), result);
    }

    private AiTimelineTaskInputResponse getInput(String taskId, String taskToken) {
        AiTimelineTaskInputResponse input = restClient.get()
                // URI template을 보존해야 Micrometer의 low-cardinality uri tag에 taskId 원문이 들어가지 않는다.
                .uri(INPUT_URL_TEMPLATE, taskId)
                .header(TASK_TOKEN_HEADER, taskToken)
                .retrieve()
                .body(AiTimelineTaskInputResponse.class);
        if (input == null || input.sourceItems() == null || input.sourceItems().isEmpty()) {
            throw new IllegalStateException("fake AI got no source items: taskId=" + taskId);
        }
        return input;
    }

    private void postResult(String taskId, String taskToken, AiTimelineResultRequest body) {
        restClient.post()
                .uri(RESULT_URL_TEMPLATE, taskId)
                .header(TASK_TOKEN_HEADER, taskToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private void postCallback(String taskId, String taskToken, DraftTaskCallbackRequest body) {
        try {
                    restClient.post()
                    .uri(CALLBACK_URL_TEMPLATE, taskId)
                    .header(TASK_TOKEN_HEADER, taskToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("fake AI callback sent: taskId={}, status={}", taskId, body.status());
        } catch (RuntimeException e) {
            log.warn("fake AI callback failed: taskId={}", taskId, e);
        }
    }

    /** 조회한 source 전부를 Event 하나로 묶는다(추론 없음 — 분류는 UNKNOWN 고정). */
    private static AiTimelineResultRequest toResult(AiTimelineTaskInputResponse input) {
        List<AiTimelineTaskInputResponse.SourceItem> sources = input.sourceItems();
        OffsetDateTime startAt = resolveStartAt(sources);
        OffsetDateTime endAt = sources.stream()
                .map(AiTimelineTaskInputResponse.SourceItem::endAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .filter(value -> !value.isBefore(startAt))
                .orElse(null);
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.UNKNOWN, FAKE_TITLE, null, startAt, endAt,
                sources.stream().map(AiTimelineTaskInputResponse.SourceItem::rawId).toList())));
    }

    /** Event startAt은 필수라 폴백 체인으로 항상 값을 만든다(source start 최솟값 → end 최솟값 → window 시작). */
    private static OffsetDateTime resolveStartAt(List<AiTimelineTaskInputResponse.SourceItem> sources) {
        return sources.stream().map(AiTimelineTaskInputResponse.SourceItem::startAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElseGet(() -> sources.stream().map(AiTimelineTaskInputResponse.SourceItem::endAt)
                        .filter(Objects::nonNull)
                        .min(Comparator.naturalOrder())
                        .orElseGet(OffsetDateTime::now));
    }

}
