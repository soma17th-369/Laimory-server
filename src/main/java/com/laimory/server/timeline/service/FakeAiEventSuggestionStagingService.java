package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineDraftEventSuggestion;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * fake AI(dev)의 이벤트 제안 staging 오케스트레이터. 실 AI의 write-then-notify 계약 중 write 절반을 대행한다 —
 * canned 이벤트 제안 1건 INSERT + 전 source 행의 event FK 배정을 <b>한 트랜잭션</b>으로 커밋한다.
 *
 * <p>트랜잭션 경계를 dispatcher와 분리한 이유: dispatcher의 {@code @Async} 메서드 안에서 자기 자신의
 * {@code @Transactional}을 부르면 self-invocation으로 무효화된다. 별도 빈을 Spring 프록시 경유로 호출해야
 * 트랜잭션이 실제로 활성화된다({@link DailyTimelineService#appendDailyTimeline} 선례).
 * 이 메서드의 리턴 = 커밋 완료이므로, 호출측은 리턴 후 콜백을 쏘면 조기 콜백(커밋 전 콜백 도착)이 구조적으로 없다.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "fake")
public class FakeAiEventSuggestionStagingService {

    static final String FAKE_TITLE = "[FAKE] 타임라인 이벤트 제안";

    private final TimelineDraftSourceItemService timelineDraftSourceItemService;
    private final TimelineDraftEventSuggestionService timelineDraftEventSuggestionService;

    /**
     * 이벤트 제안 1건 INSERT + 전 source FK UPDATE를 한 트랜잭션으로 staging한다.
     *
     * @return false = source 행 없음(staging 미수행 — 호출측이 FAILED 콜백)
     */
    @Transactional
    public boolean stage(String taskId) {
        List<TimelineDraftSourceItem> sources = timelineDraftSourceItemService.findByTaskId(taskId);
        if (sources.isEmpty()) {
            return false;
        }
        LocalDateTime startAt = resolveStartAt(sources);
        TimelineDraftEventSuggestion suggestion = timelineDraftEventSuggestionService.save(
                TimelineDraftEventSuggestion.of(taskId, sources.get(0).getUserId(), startAt,
                        resolveEndAt(sources, startAt), FAKE_TITLE, null));
        sources.forEach(source ->
                source.assignEventSuggestion(suggestion.getTimelineDraftEventSuggestionId()));
        timelineDraftSourceItemService.saveAll(sources);
        return true;
    }

    /** finalize 검증기가 이벤트 startAt NOT NULL을 요구하므로 폴백 체인으로 항상 값을 만든다. */
    private LocalDateTime resolveStartAt(List<TimelineDraftSourceItem> sources) {
        return sources.stream().map(TimelineDraftSourceItem::getStartAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElseGet(() -> sources.stream().map(TimelineDraftSourceItem::getEndAt).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder())
                        .orElseGet(LocalDateTime::now));
    }

    /** non-null endAt 최댓값. startAt보다 이전이면 null(단일 시점 이벤트) — 검증기의 endAt≥startAt 위반 방지. */
    private LocalDateTime resolveEndAt(List<TimelineDraftSourceItem> sources, LocalDateTime startAt) {
        return sources.stream().map(TimelineDraftSourceItem::getEndAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .filter(endAt -> !endAt.isBefore(startAt))
                .orElse(null);
    }
}
