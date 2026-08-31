package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.common.id.UuidV7;
import com.laimory.server.common.privacy.PrivacyRedactor;
import com.laimory.server.timeline.RawIds;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import com.laimory.server.timeline.dto.TimelineAiTestAiRequest;
import com.laimory.server.timeline.dto.TimelineAiTestRequest;
import com.laimory.server.timeline.dto.TimelineAiTestResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * dev 전용 AI 동기 테스트 오케스트레이터(#394) — AI Timeline Input을 받아 AI 동기 endpoint로 전달하고
 * 추론 결과를 그대로 돌려준다.
 *
 * <p><b>이 경로는 MySQL·Redis·draft task를 일절 건드리지 않는다.</b> 협력자는 AI client, 검증된 설정,
 * 공용 privacy redactor 셋뿐이며(redactor는 상태 없는 component라 저장소 의존을 끌고 오지 않는다),
 * 그래서 회원·Daily Record·Draft Task 없이도 AI 품질과 계약을 확인할 수 있다.
 *
 * <p><b>인증은 이 서비스의 책임이 아니다.</b> {@code /t/api} 경로의 Bearer token 검증은 security 계층이
 * 소유하며, 여기까지 온 요청은 이미 통과한 것으로 취급한다. draft의 회전 task token 기계(Redis
 * {@code tokenHash}·{@code ProcessStage}·retry receipt)와도 아무 연결이 없다 — 동기 1회 요청이라
 * 단계별 회전·재발급이라는 개념 자체가 성립하지 않는다. 회원 식별도 하지 않는다.
 *
 * <p>{@code taskId}는 서버가 발행해 AI 요청과 응답 양쪽에 싣는다. AI가 조회·저장에 쓰지는 않지만 같은
 * {@code taskId}로 돌린 비동기 실행과 AI 로그·Langfuse에서 이어 볼 수 있는 상관키다.
 *
 * <p>AI로 나가는 텍스트는 운영과 같은 v1 privacy 치환을 거친다 — 운영 AI가 보는 것이 치환본이므로,
 * 치환하지 않으면 오히려 운영과 다른 입력으로 품질을 재게 된다. {@code photoUrl}·{@code filename}은
 * 제외 대상이라 원문으로 나간다(AI가 {@code photoUrl}로 실제 이미지를 GET한다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai.timeline-test.enabled", havingValue = "true")
public class TimelineAiTestService {

    /**
     * 운영 staging 치환({@code TimelineDraftTaskService}의 {@code STORAGE_REDACTION_EXCLUDED_FIELDS})과
     * 같은 제외 집합이다. {@code clientPhotoUri}는 여기서 제외한 뒤 아래에서 고정 token으로 덮고,
     * {@code filename}·{@code photoUrl}은 원문 그대로 나간다.
     */
    private static final Set<String> AI_REDACTION_EXCLUDED_FIELDS =
            Set.of("clientPhotoUri", "filename", "photoUrl");

    private final TimelineAiTestClient timelineAiTestClient;
    private final PrivacyRedactor privacyRedactor;

    public TimelineAiTestResponse generate(String applicationVersion, TimelineAiTestRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        requireValidRequest(request);

        String taskId = UuidV7.randomUuidV7().toString();
        TimelineAiTestAiRequest aiRequest = toAiRequest(taskId, request);
        long startedAt = System.nanoTime();
        try {
            TimelineAiTestResponse response = timelineAiTestClient.generate(aiRequest);
            log.info("timeline ai test completed: taskId={} sourceItems={} events={} timedOut={} elapsedMs={}",
                    taskId, aiRequest.sourceItems().size(), response.events().size(), response.timedOut(),
                    elapsedMs(startedAt));
            return response;
        } catch (TimelineAiTestCallException e) {
            // 자유 text error는 담지도 로그하지도 않는다 — numeric code와 status만 남긴다.
            log.warn("timeline ai test failed: taskId={} aiStatus={} aiErrorCode={} reason={} elapsedMs={}",
                    taskId, e.getAiStatus(), e.getAiErrorCode(), e.getReason(), elapsedMs(startedAt));
            throw e;
        }
    }

    /**
     * AI가 5xx로 낼 입력 오류를 앞에서 400으로 거른다 — {@code window} 누락은 AI 422 {@code 1001},
     * sourceItems 0건·rawId 중복은 AI 500 {@code 1102}다. 그 밖의 해석은 하지 않는다(payload는 통과).
     *
     * <p>예외 메시지에 사용자 입력을 넣지 않는다 — 위치는 index로만 가리킨다.
     */
    private static void requireValidRequest(TimelineAiTestRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body가 필요합니다.");
        }
        if (request.recordDate() == null) {
            throw new IllegalArgumentException("recordDate는 필수입니다.");
        }
        AiTimelineTaskInputResponse.Window window = request.window();
        if (window == null || window.startAt() == null || window.endAt() == null) {
            throw new IllegalArgumentException("window.startAt과 window.endAt은 필수입니다.");
        }
        if (!window.startAt().isBefore(window.endAt())) {
            throw new IllegalArgumentException("window.startAt은 window.endAt보다 앞서야 합니다.");
        }
        List<AiTimelineTaskInputResponse.SourceItem> items = request.sourceItems();
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("sourceItems는 1건 이상이어야 합니다.");
        }
        Set<String> rawIds = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            AiTimelineTaskInputResponse.SourceItem item = items.get(index);
            if (item == null || item.itemType() == null || item.startAt() == null) {
                throw new IllegalArgumentException(
                        "sourceItems[" + index + "]의 itemType과 startAt은 필수입니다.");
            }
            if (!RawIds.isCanonicalUuid(item.rawId())) {
                throw new IllegalArgumentException(
                        "sourceItems[" + index + "].rawId는 canonical lowercase UUID여야 합니다.");
            }
            if (!rawIds.add(item.rawId())) {
                throw new IllegalArgumentException("sourceItems[" + index + "].rawId가 중복입니다.");
            }
        }
    }

    /** 호출자 body에 서버 발행 {@code taskId}를 붙이고 AI로 나가는 텍스트를 v1 치환한다. */
    private TimelineAiTestAiRequest toAiRequest(String taskId, TimelineAiTestRequest request) {
        return new TimelineAiTestAiRequest(
                taskId,
                request.recordDate(),
                // recordTimeZone 기본값(Asia/Seoul)은 AI가 소유한다 — 서버가 채워 넣지 않는다.
                request.recordTimeZone(),
                request.window(),
                redactUserMemory(request.userMemory()),
                request.sourceItems().stream().map(this::redactForAi).toList());
    }

    /** User Memory 문서는 운영 접수와 같이 제외 없이 textual leaf 전체를 치환한다(구조·필드 집합 불변). */
    private JsonNode redactUserMemory(JsonNode userMemory) {
        return userMemory == null ? null : privacyRedactor.redactTree(userMemory).node();
    }

    /**
     * 운영의 두 지점(staging 치환 + 입력 조회 응답의 {@code clientPhotoUri} 치환)을 한 pass로 합친다 —
     * 이 경로에는 staging이 없어 전달 직전에 둘 다 해야 결과가 운영 입력과 같아진다.
     */
    private AiTimelineTaskInputResponse.SourceItem redactForAi(AiTimelineTaskInputResponse.SourceItem item) {
        if (item.payload() == null) {
            return item;
        }
        JsonNode redacted = privacyRedactor.redactTree(item.payload(), AI_REDACTION_EXCLUDED_FIELDS).node();
        return new AiTimelineTaskInputResponse.SourceItem(
                item.rawId(), item.itemType(), item.startAt(), item.endAt(),
                TimelineAiTaskInputService.redactClientPhotoUri(redacted));
    }

    private static long elapsedMs(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
