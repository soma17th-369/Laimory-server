package com.laimory.server.timeline.service;

import com.laimory.server.common.ApiUrls;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * dev 전용 fake AI 디스패처. 실 AI 역할을 in-process로 대행하는 dev runtime 시뮬레이터다 — 앱이 dev
 * 서버에서 draft→direct-write→callback→SUCCESS 흐름을 실제로 태워볼 수 있도록, delay(추론 시간 흉내) 후
 * final Event/Item/junction을 직접 커밋({@link FakeAiTimelineAppendService})하고, 자기 서버의 콜백
 * 엔드포인트를 <b>실제 HTTP</b>로 호출한다(commit-then-callback). 토큰이 프로세스 밖으로 나가지 않아
 * 보안 모델이 유지된다.
 *
 * <p>실 AI 계약과의 의도적 차이: <b>콜백 재시도 없음</b>(dev 도구). 콜백 HTTP가 실패하면 task는
 * PROCESSING인 채 TTL로 소멸한다(final graph는 commit대로 남는다 — 실 AI의 "commit 후 callback 유실"
 * MVP 한계와 같은 상태). 서버가 8080이 아닌 포트로 떠 있으면 콜백이 유실된다(고정 URL 전제).
 *
 * <p>⚠️ {@code callbackToken}은 비밀 — 어떤 로그에도 포함하지 않는다(헤더로만 전송).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "fake")
public class FakeTimelineAiDispatcher implements TimelineAiDispatcher {

    // 콜백 경로 중 /timeline/drafts/{taskId}/callback은 TimelineCallbackController 매핑의 복제다
    // (서비스가 컨트롤러 상수를 참조하는 레이어 역류를 피함). URL 형태는 FakeTimelineAiDispatcherTest,
    // 컨트롤러 매핑은 TimelineCallbackControllerTest가 각각 소유하며, 둘의 조합 드리프트를 자동 검출하는 테스트는 없다.
    private static final String CALLBACK_URL_TEMPLATE =
            "http://localhost:8080" + ApiUrls.SERVER_API_URL.replace(ApiUrls.VERSION, "v1")
                    + "/timeline/drafts/{taskId}/callback";

    private final FakeAiTimelineAppendService fakeAiTimelineAppendService;
    private final RestClient restClient;
    private final Duration callbackDelay;

    // RestClient build + delay 프로퍼티 주입이 있어 @RequiredArgsConstructor 대신 명시적 생성자를 쓴다.
    // requestFactory는 커스텀하지 않는다 — 단위 테스트의 MockRestServiceServer.bindTo(builder)가 심는
    // mock factory를 덮어버리기 때문(dev 전용이라 기본 타임아웃 수용).
    public FakeTimelineAiDispatcher(
            FakeAiTimelineAppendService fakeAiTimelineAppendService,
            RestClient.Builder restClientBuilder,
            @Value("${app.ai.fake.callback-delay:2s}") Duration callbackDelay) {
        this.fakeAiTimelineAppendService = fakeAiTimelineAppendService;
        this.restClient = restClientBuilder.build();
        this.callbackDelay = callbackDelay;
    }

    @Async
    @Override
    public void dispatch(AiTimelineDispatchRequest request) {
        // delay를 append 앞에 둔다: 앱이 PROCESSING을 관찰할 수 있고, 실 AI 동작(추론 시간 → commit과
        // callback은 붙어서)과 일치한다. 기본 2s는 단위 테스트에선 ZERO로 대체된다(생성자 주입).
        try {
            Thread.sleep(callbackDelay.toMillis());
        } catch (InterruptedException e) {
            // 셧다운 시그널 — append 전이므로 중단하면 찌꺼기가 없다(task는 PROCESSING TTL로 소멸).
            Thread.currentThread().interrupt();
            return;
        }

        String taskId = request.taskId();
        DraftTaskCallbackRequest result;
        try {
            FakeAiTimelineAppendService.AppendResult appendResult =
                    fakeAiTimelineAppendService.append(taskId, request.dailyRecordId());
            result = appendResult == FakeAiTimelineAppendService.AppendResult.SUCCESS
                    ? new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null, null)
                    : new DraftTaskCallbackRequest(TaskStatus.FAILED, ExceptionType.AI_REPORTED_FAILURE.code(),
                            "fake validation failed");
        } catch (RuntimeException e) {
            log.warn("fake AI append failed: taskId={}", taskId, e);
            // 상세 예외는 위 로그에만 — 콜백 서비스가 error를 또 로깅하므로 고정 문구로 이중 노출을 피한다.
            result = new DraftTaskCallbackRequest(TaskStatus.FAILED, ExceptionType.AI_REPORTED_FAILURE.code(),
                    "fake append failed");
        }
        // append()가 리턴했다 = final 트랜잭션 커밋 완료. 여기서부터가 callback(조기 콜백 구조적 차단).
        postCallback(taskId, request.callbackToken(), result);
    }

    private void postCallback(String taskId, String callbackToken, DraftTaskCallbackRequest body) {
        try {
            restClient.post()
                    // URI template을 보존해야 Micrometer의 low-cardinality uri tag에 taskId 원문이 들어가지 않는다.
                    .uri(CALLBACK_URL_TEMPLATE, taskId)
                    .header("Callback-Token", callbackToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("fake AI callback sent: taskId={}, status={}", taskId, body.status());
        } catch (RuntimeException e) {
            log.warn("fake AI callback failed: taskId={}", taskId, e);
        }
    }
}
