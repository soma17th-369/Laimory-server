package com.laimory.server.timeline.service;

import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * fake AI(dev)의 direct-write append 오케스트레이터. 실 AI의 final transaction 계약을 in-process로 대행한다 —
 * validation(record 존재/DRAFT/owner 일치/기존 rawId 재검사) → canned Event 1건 + source별 Item + junction
 * INSERT → 채택 source 전량 DELETE를 <b>한 트랜잭션</b>으로 커밋한다(append-only — 기존 graph 불변).
 *
 * <p>트랜잭션 경계를 dispatcher와 분리한 이유: dispatcher의 {@code @Async} 메서드 안에서 자기 자신의
 * {@code @Transactional}을 부르면 self-invocation으로 무효화된다. 별도 빈을 Spring 프록시 경유로 호출해야
 * 트랜잭션이 실제로 활성화된다. 이 메서드의 리턴 = 커밋 완료이므로, 호출측은 리턴 후 콜백을 쏘면
 * 조기 콜백(커밋 전 콜백 도착)이 구조적으로 없다(실 AI의 commit-then-callback과 동일).
 *
 * <p>실 AI가 하는 +10분 startAt collision nudge/endAt clamp도 같은 규칙으로 적용해 dev 동작을 계약과
 * 정렬한다. inference는 없으므로 분류는 {@code UNKNOWN} 고정, 모든 source를 한 Event로 묶는다.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "fake")
public class FakeAiTimelineAppendService {

    static final String FAKE_TITLE = "[FAKE] 타임라인 이벤트 제안";
    private static final Duration START_AT_COLLISION_NUDGE = Duration.ofMinutes(10);

    /** append 결과 — 호출측(dispatcher)이 콜백 status/errorCode로 변환한다. */
    public enum AppendResult {
        SUCCESS,
        /** source 없음·record 없음/DRAFT 아님/owner 불일치·기존 rawId 재검사 위반 — validation FAILED. */
        VALIDATION_FAILED
    }

    private final DailyRecordService dailyRecordService;
    private final TimelineDraftSourceItemService timelineDraftSourceItemService;
    private final TimelineEventService timelineEventService;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;

    /**
     * 실 AI final transaction 대행: validation → Event/Item/junction INSERT → 채택 source DELETE.
     * 리턴 = 커밋 완료(SUCCESS인 경우). VALIDATION_FAILED는 아무것도 쓰지 않는다.
     */
    @Transactional
    public AppendResult append(String taskId, long dailyRecordId) {
        List<TimelineDraftSourceItem> sources = timelineDraftSourceItemService.findByTaskId(taskId);
        if (sources.isEmpty()) {
            return AppendResult.VALIDATION_FAILED;
        }
        DailyRecord record = dailyRecordService.findById(dailyRecordId).orElse(null);
        if (record == null || record.getStatus() != DailyRecordStatus.DRAFT
                || sources.stream().anyMatch(source -> !Objects.equals(source.getUserId(), record.getUserId()))) {
            return AppendResult.VALIDATION_FAILED;
        }

        // append 중복 재검사(write 직전): 이 record의 기존 final Item에 같은 rawId가 있으면 validation FAILED
        // (API 사전 제외와 이중 방어 — 실 AI 계약과 동일).
        List<TimelineEvent> existingEvents = timelineEventService.findByDailyRecordId(dailyRecordId);
        List<Long> existingItemIds = timelineEventItemService.findByTimelineEventIds(
                        existingEvents.stream().map(TimelineEvent::getTimelineEventId).toList()).stream()
                .map(TimelineEventItem::getTimelineItemId)
                .distinct()
                .toList();
        Set<String> savedRawIds = timelineItemService.findSavedRawIds(existingItemIds,
                sources.stream().map(TimelineDraftSourceItem::getRawId).toList());
        if (!savedRawIds.isEmpty()) {
            return AppendResult.VALIDATION_FAILED;
        }

        // 실 AI의 startAt collision nudge/endAt clamp와 같은 규칙(정확히 겹칠 때만 +10분씩 cascade).
        Set<LocalDateTime> occupiedStartAts = new HashSet<>();
        existingEvents.forEach(event -> occupiedStartAts.add(event.getStartAt()));
        LocalDateTime startAt = resolveNonCollidingStartAt(resolveStartAt(sources), occupiedStartAts);
        LocalDateTime endAt = clampEndAt(resolveEndAt(sources, startAt), startAt);

        TimelineEvent savedEvent = timelineEventService.save(
                TimelineEvent.of(dailyRecordId, TimelineEventType.UNKNOWN, startAt, endAt, FAKE_TITLE, null));
        List<TimelineEventItem> links = sources.stream()
                .map(source -> {
                    TimelineItem item = timelineItemService.save(TimelineItem.of(
                            source.getItemType(), source.getRawId(),
                            source.getStartAt(), source.getEndAt(), source.getPayload()));
                    return TimelineEventItem.of(savedEvent.getTimelineEventId(), item.getTimelineItemId());
                })
                .toList();
        timelineEventItemService.saveAll(links);
        // 채택한 source 전량 삭제(fake는 전부 채택) — 실 AI 계약처럼 final write와 같은 트랜잭션이다.
        timelineDraftSourceItemService.deleteByTaskId(taskId);
        return AppendResult.SUCCESS;
    }

    /** Event startAt NOT NULL 계약을 위해 폴백 체인으로 항상 값을 만든다(source start 최솟값 → end 최솟값 → now). */
    private LocalDateTime resolveStartAt(List<TimelineDraftSourceItem> sources) {
        return sources.stream().map(TimelineDraftSourceItem::getStartAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElseGet(() -> sources.stream().map(TimelineDraftSourceItem::getEndAt).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder())
                        .orElseGet(LocalDateTime::now));
    }

    /** non-null endAt 최댓값. startAt보다 이전이면 null(단일 시점 이벤트). */
    private LocalDateTime resolveEndAt(List<TimelineDraftSourceItem> sources, LocalDateTime startAt) {
        return sources.stream().map(TimelineDraftSourceItem::getEndAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .filter(endAt -> !endAt.isBefore(startAt))
                .orElse(null);
    }

    /** 기존/이미 배정된 start_at과 정확히 겹치면 빈 슬롯까지 +10분씩 민다(cascade). */
    private LocalDateTime resolveNonCollidingStartAt(LocalDateTime startAt, Set<LocalDateTime> occupied) {
        LocalDateTime resolved = startAt;
        while (!occupied.add(resolved)) {
            resolved = resolved.plus(START_AT_COLLISION_NUDGE);
        }
        return resolved;
    }

    /** nudge로 start_at이 밀려 end_at보다 뒤가 되면 end_at을 start_at으로 클램프한다(0분 구간). null end_at은 그대로. */
    private LocalDateTime clampEndAt(LocalDateTime endAt, LocalDateTime startAt) {
        if (endAt != null && endAt.isBefore(startAt)) {
            return startAt;
        }
        return endAt;
    }
}
