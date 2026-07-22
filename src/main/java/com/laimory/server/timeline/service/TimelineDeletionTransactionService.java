package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 타임라인 삭제의 DB 트랜잭션 경계 전담 빈. S3 배치 삭제(트랜잭션 밖) 성공 후
 * {@link TimelineDeletionService}가 Spring 프록시를 통해 호출한다 — 오케스트레이터 안에
 * {@code @Transactional} 메서드를 두면 self-invocation으로 트랜잭션이 조용히 무효화되므로 분리한다.
 *
 * <p>S3 삭제 동안 상태가 변했을 수 있어(사전 검증과 시차) 짧은 트랜잭션 안에서 소유권·DRAFT를
 * <b>재확인</b>한다. Event/DailyRecord 행 삭제 시 자기 junction 행은 DB FK {@code ON DELETE CASCADE}가
 * 지우지만, Item에는 record FK가 없어 cascade되지 않는다 — 삭제 후 association이 0이 될 Item(orphan)을
 * 같은 트랜잭션에서 명시적으로 지운다. orphan 판정은 삭제 <b>전</b> junction을 읽어 "삭제 대상 Event에만
 * 연결된 후보"로 계산한다(엔티티 삭제 SQL은 flush 시점이라 삭제 후 재조회는 stale 읽기 위험이 있다 —
 * 같은 날짜 쓰기는 date guard가 직렬화하므로 삭제 전 스냅샷 판정이 안전하다).
 */
@Service
@RequiredArgsConstructor
public class TimelineDeletionTransactionService {

    private final TimelineEventService timelineEventService;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;
    private final DailyRecordService dailyRecordService;

    /** 소유권·DRAFT 재확인 후 이벤트 행과 이 이벤트에만 연결된 orphan Item을 삭제한다(junction은 DB cascade). */
    @Transactional
    public void deleteEvent(long userId, Long timelineEventId) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        requireOwnedDraftRecord(userId, event.getDailyRecordId(), ExceptionType.TIMELINE_EVENT_NOT_FOUND);

        List<Long> orphanItemIds = resolveOrphanItemIds(Set.of(timelineEventId));
        timelineEventService.deleteById(timelineEventId);
        timelineItemService.deleteByIds(orphanItemIds);
    }

    /** 소유권·DRAFT 재확인 후 하루 기록 행과 이 record의 Event에만 연결된 orphan Item을 삭제한다(events/junction은 DB cascade). */
    @Transactional
    public void deleteDailyRecord(long userId, Long dailyRecordId) {
        requireOwnedDraftRecord(userId, dailyRecordId, ExceptionType.DAILY_RECORD_NOT_FOUND);

        Set<Long> recordEventIds = timelineEventService.findByDailyRecordId(dailyRecordId).stream()
                .map(TimelineEvent::getTimelineEventId)
                .collect(Collectors.toSet());
        List<Long> orphanItemIds = resolveOrphanItemIds(recordEventIds);
        dailyRecordService.deleteById(dailyRecordId);
        timelineItemService.deleteByIds(orphanItemIds);
    }

    /**
     * 삭제될 Event 집합에<b>만</b> 연결된 Item ID들(= 삭제 후 association 0이 될 orphan)을 계산한다.
     * 삭제 대상 밖 Event에도 연결된 Item은 shared로 보고 유지한다 — 정상 write 경로에선 same-record 규칙으로
     * cross-record 후보가 없어야 하지만, 있어도 방어적으로 유지된다.
     */
    private List<Long> resolveOrphanItemIds(Set<Long> deletedEventIds) {
        List<Long> candidateItemIds = timelineEventItemService.findByTimelineEventIds(deletedEventIds).stream()
                .map(TimelineEventItem::getTimelineItemId)
                .distinct()
                .toList();
        Map<Long, Set<Long>> eventIdsByItemId = timelineEventItemService.findByTimelineItemIds(candidateItemIds)
                .stream()
                .collect(Collectors.groupingBy(TimelineEventItem::getTimelineItemId,
                        Collectors.mapping(TimelineEventItem::getTimelineEventId, Collectors.toSet())));
        return candidateItemIds.stream()
                .filter(itemId -> deletedEventIds.containsAll(
                        eventIdsByItemId.getOrDefault(itemId, new HashSet<>())))
                .toList();
    }

    /** record 없음·비소유는 {@code notFoundType}(404 은닉), SAVED는 409(ERROR_1003)로 거절한다. */
    private DailyRecord requireOwnedDraftRecord(long userId, Long dailyRecordId, ExceptionType notFoundType) {
        DailyRecord record = dailyRecordService.findById(dailyRecordId)
                .filter(owned -> owned.getUserId() == userId)
                .orElseThrow(() -> new BusinessException(notFoundType));
        if (record.getStatus() == DailyRecordStatus.SAVED) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
        }
        return record;
    }
}
