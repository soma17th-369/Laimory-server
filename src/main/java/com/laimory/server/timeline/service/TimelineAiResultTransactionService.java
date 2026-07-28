package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineAiResultReceipt;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 결과 저장 트랜잭션 경계 전담 빈 — 과거 AI가 direct-write하던 final transaction을 서버가 이어받은 것이다.
 * {@link TimelineAiResultService}가 Spring 프록시를 통해 호출한다(self-invocation 회피).
 *
 * <p>순서가 load-bearing이다:
 * <ol>
 *   <li><b>receipt INSERT + flush를 가장 먼저</b> 한다. 같은 task의 재시도·동시 중복 요청은 여기서 duplicate
 *       key로 갈리며(호출부가 "이미 반영"으로 변환), 이후 검증·write를 헛돌지 않는다. PK 하나가 직렬화를
 *       담당하므로 DailyRecord pessimistic lock은 두지 않는다 — 기존 writer(Event PATCH·삭제) 중 어느 것도
 *       그 lock을 잡지 않아 조합되지 않기 때문이다.</li>
 *   <li>DB 사실 기반 검증(record 상태, source 소유, 기존 final rawId 중복)</li>
 *   <li>record timezone wall-clock 정규화 → startAt 충돌 nudge/endAt clamp</li>
 *   <li>Item(distinct rawId당 1행)·Event·junction INSERT</li>
 *   <li>채택된 staging source만 DELETE</li>
 * </ol>
 * 어느 단계에서 실패하든 receipt를 포함한 전부가 함께 롤백된다 — 부분 반영된 graph가 남지 않는다.
 *
 * <p>append-only 계약을 유지한다: 기존 Event/Item/junction/memo를 수정·삭제하지 않는다. startAt 충돌
 * +10분 nudge와 endAt clamp는 AI writer가 소유하던 규칙을 그대로 이어받은 것이다(정확히 겹칠 때만 적용).
 */
@Service
@RequiredArgsConstructor
public class TimelineAiResultTransactionService {

    private static final Duration START_AT_COLLISION_NUDGE = Duration.ofMinutes(10);
    private static final int TITLE_MAX_LENGTH = 255;
    private static final int SUBTITLE_MAX_LENGTH = 255;

    private final DailyRecordService dailyRecordService;
    private final TimelineDraftSourceItemService timelineDraftSourceItemService;
    private final TimelineEventService timelineEventService;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;
    private final TimelineAiResultReceiptService timelineAiResultReceiptService;

    /**
     * AI 결과를 final graph에 반영한다. 리턴 = 커밋 예정(호출 트랜잭션 종료 시 commit).
     * 같은 taskId 영수증이 이미 있으면 duplicate key 예외가 전파된다 — 호출부가 "이미 반영"으로 변환한다.
     */
    @Transactional
    public void store(String taskId, long dailyRecordId, AiTimelineResultRequest request) {
        // 1. 멱등성 게이트. 이후 어떤 검증·write보다 먼저 자리를 선점한다.
        timelineAiResultReceiptService.saveNew(TimelineAiResultReceipt.of(taskId, dailyRecordId));

        DailyRecord record = dailyRecordService.findById(dailyRecordId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));
        if (record.getStatus() != DailyRecordStatus.DRAFT) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
        }

        // 2. 결과가 참조하는 rawId가 이 task의 staging source인지 확인한다(타 task·임의 값 차단).
        Map<String, TimelineDraftSourceItem> sourcesByRawId = timelineDraftSourceItemService.findByTaskId(taskId)
                .stream()
                .collect(Collectors.toMap(TimelineDraftSourceItem::getRawId, Function.identity(),
                        (first, duplicate) -> first, LinkedHashMap::new));
        List<String> adoptedRawIds = distinctRawIds(request);
        List<String> unknownRawIds = adoptedRawIds.stream()
                .filter(rawId -> !sourcesByRawId.containsKey(rawId))
                .toList();
        if (!unknownRawIds.isEmpty()) {
            // rawId 원문은 클라 입력값이라 개수만 남긴다.
            throw new IllegalArgumentException(
                    "result references source items outside this task: count=" + unknownRawIds.size());
        }

        // 3. 이 record의 기존 final Item과 rawId가 겹치면 거절한다(draft POST 사전 제외와 이중 방어).
        List<TimelineEvent> existingEvents = timelineEventService.findByDailyRecordId(dailyRecordId);
        List<Long> existingItemIds = timelineEventItemService.findByTimelineEventIds(
                        existingEvents.stream().map(TimelineEvent::getTimelineEventId).toList()).stream()
                .map(TimelineEventItem::getTimelineItemId)
                .distinct()
                .toList();
        Set<String> alreadySavedRawIds = timelineItemService.findSavedRawIds(existingItemIds, adoptedRawIds);
        if (!alreadySavedRawIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "result references already saved rawIds: count=" + alreadySavedRawIds.size());
        }

        // 4. 채택 source마다 final Item 한 행(여러 Event가 공유해도 1행 — junction으로만 연결).
        ZoneId recordZone = ZoneId.of(record.getRecordTimezone());
        Map<String, Long> itemIdsByRawId = new LinkedHashMap<>();
        for (String rawId : adoptedRawIds) {
            TimelineDraftSourceItem source = sourcesByRawId.get(rawId);
            TimelineItem item = timelineItemService.save(TimelineItem.of(
                    source.getItemType(), source.getRawId(),
                    source.getStartAt(), source.getEndAt(), source.getPayload()));
            itemIdsByRawId.put(rawId, item.getTimelineItemId());
        }

        // 5. Event append. startAt 충돌 회피는 기존 Event와 이번에 배정한 Event를 함께 본다.
        Set<LocalDateTime> occupiedStartAts = existingEvents.stream()
                .map(TimelineEvent::getStartAt)
                .collect(Collectors.toCollection(HashSet::new));
        List<TimelineEventItem> links = new ArrayList<>();
        for (AiTimelineResultRequest.Event event : request.events()) {
            LocalDateTime startAt = resolveNonCollidingStartAt(
                    toRecordLocal(event.startAt(), recordZone), occupiedStartAts);
            LocalDateTime endAt = clampEndAt(toRecordLocal(event.endAt(), recordZone), startAt);
            TimelineEvent savedEvent = timelineEventService.save(TimelineEvent.of(
                    dailyRecordId, event.eventType(), startAt, endAt,
                    event.title().trim(), trimToNull(event.subtitle())));
            for (String rawId : new LinkedHashSet<>(event.sourceRawIds())) {
                links.add(TimelineEventItem.of(savedEvent.getTimelineEventId(), itemIdsByRawId.get(rawId)));
            }
        }
        timelineEventItemService.saveAll(links);

        // 6. 채택된 staging만 삭제한다(누락 source는 retention cleanup 대상으로 남긴다).
        timelineDraftSourceItemService.deleteAdopted(taskId, adoptedRawIds);
    }

    /**
     * 결과 body 자체의 형식을 검증한다(DB 조회 없음 — transaction 밖 호출부가 먼저 부른다).
     * 위반은 {@link IllegalArgumentException}으로 400이 되며, 메시지는 로그로만 남고 응답에 노출되지 않는다.
     */
    static void requireValidShape(AiTimelineResultRequest request) {
        if (request == null || request.events() == null || request.events().isEmpty()) {
            throw new IllegalArgumentException("result requires at least one event");
        }
        for (int i = 0; i < request.events().size(); i++) {
            AiTimelineResultRequest.Event event = request.events().get(i);
            if (event == null) {
                throw new IllegalArgumentException("event is null: index=" + i);
            }
            if (event.eventType() == null) {
                throw new IllegalArgumentException("event requires eventType: index=" + i);
            }
            if (isBlank(event.title())) {
                throw new IllegalArgumentException("event requires title: index=" + i);
            }
            if (event.title().trim().length() > TITLE_MAX_LENGTH) {
                throw new IllegalArgumentException("event title is too long: index=" + i);
            }
            if (event.subtitle() != null && event.subtitle().trim().length() > SUBTITLE_MAX_LENGTH) {
                throw new IllegalArgumentException("event subtitle is too long: index=" + i);
            }
            if (event.startAt() == null) {
                throw new IllegalArgumentException("event requires startAt: index=" + i);
            }
            if (event.endAt() != null && event.endAt().isBefore(event.startAt())) {
                throw new IllegalArgumentException("event endAt precedes startAt: index=" + i);
            }
            if (event.sourceRawIds() == null || event.sourceRawIds().isEmpty()) {
                throw new IllegalArgumentException("event requires at least one sourceRawId: index=" + i);
            }
            if (event.sourceRawIds().stream().anyMatch(TimelineAiResultTransactionService::isBlank)) {
                throw new IllegalArgumentException("event has blank sourceRawId: index=" + i);
            }
        }
    }

    /** 결과 전체가 채택한 rawId를 입력 순서대로 중복 없이 모은다(Item 생성 축). */
    private static List<String> distinctRawIds(AiTimelineResultRequest request) {
        return request.events().stream()
                .flatMap(event -> event.sourceRawIds().stream())
                .distinct()
                .toList();
    }

    /**
     * offset 시각을 record timezone의 wall-clock으로 정규화한다 — MySQL {@code DATETIME}이 offset을
     * 보존하지 않으므로, 저장 값은 항상 record timezone 기준 local 시각이어야 한다.
     */
    private static LocalDateTime toRecordLocal(OffsetDateTime value, ZoneId recordZone) {
        return value == null ? null : value.atZoneSameInstant(recordZone).toLocalDateTime();
    }

    /** 기존/이미 배정된 start_at과 정확히 겹치면 빈 슬롯까지 +10분씩 민다(cascade — AI writer 규칙 승계). */
    private static LocalDateTime resolveNonCollidingStartAt(LocalDateTime startAt, Set<LocalDateTime> occupied) {
        LocalDateTime resolved = startAt;
        while (!occupied.add(resolved)) {
            resolved = resolved.plus(START_AT_COLLISION_NUDGE);
        }
        return resolved;
    }

    /** nudge로 start_at이 밀려 end_at보다 뒤가 되면 end_at을 start_at으로 클램프한다. null end_at은 그대로. */
    private static LocalDateTime clampEndAt(LocalDateTime endAt, LocalDateTime startAt) {
        if (endAt != null && endAt.isBefore(startAt)) {
            return startAt;
        }
        return endAt;
    }

    private static String trimToNull(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
