package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 타임라인 삭제의 DB 트랜잭션 경계 전담 빈. {@link TimelineDeletionService}가 Spring 프록시를 통해
 * 호출한다 — 오케스트레이터 안에
 * {@code @Transactional} 메서드를 두면 self-invocation으로 트랜잭션이 조용히 무효화되므로 분리한다.
 *
 * <p>짧은 트랜잭션 안에서 소유권·DRAFT를 <b>재확인</b>하고, 삭제 후 association이 0이 될 PHOTO Item의
 * S3 삭제 job을 insert하고 원문 PHOTO Item을 보존한 뒤 root/junction/non-PHOTO orphan Item을 hard
 * delete한다. 따라서 job insert나 hard delete 어느 쪽이 실패해도 함께 rollback된다. S3 호출과 PHOTO
 * Item 최종 hard delete는 이 transaction 밖의 worker/completion transaction만 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineDeletionTransactionService {

    private final TimelineEventService timelineEventService;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;
    private final DailyRecordService dailyRecordService;
    private final TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;
    private final ObjectMapper objectMapper;

    /** 소유권·DRAFT 재확인 후 PHOTO Item/job 보존과 Event/non-PHOTO orphan hard delete를 한 commit으로 묶는다. */
    @Transactional
    public DeletionResult deleteEvent(long userId, Long timelineEventId) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        requireOwnedDraftRecord(userId, event.getDailyRecordId(), ExceptionType.TIMELINE_EVENT_NOT_FOUND);

        DeletionItems deletionItems = resolveDeletionItems(Set.of(timelineEventId));
        OrphanPreparation preparation = prepareOrphanItems(deletionItems.orphanItems(), userId);
        timelineEventService.deleteById(timelineEventId);
        timelineItemService.deleteByIds(preparation.immediateDeleteItemIds());
        return new DeletionResult(
                preparation.scheduled(), deletionItems.sharedPhotoCount(), preparation.invalidSkipped());
    }

    /** 소유권·DRAFT 재확인 후 PHOTO Item/job 보존과 DailyRecord/non-PHOTO orphan hard delete를 한 commit으로 묶는다. */
    @Transactional
    public DeletionResult deleteDailyRecord(long userId, Long dailyRecordId) {
        requireOwnedDraftRecord(userId, dailyRecordId, ExceptionType.DAILY_RECORD_NOT_FOUND);

        Set<Long> recordEventIds = timelineEventService.findByDailyRecordId(dailyRecordId).stream()
                .map(TimelineEvent::getTimelineEventId)
                .collect(Collectors.toSet());
        DeletionItems deletionItems = resolveDeletionItems(recordEventIds);
        OrphanPreparation preparation = prepareOrphanItems(deletionItems.orphanItems(), userId);
        dailyRecordService.deleteById(dailyRecordId);
        timelineItemService.deleteByIds(preparation.immediateDeleteItemIds());
        return new DeletionResult(
                preparation.scheduled(), deletionItems.sharedPhotoCount(), preparation.invalidSkipped());
    }

    /**
     * Event에서 PHOTO Item 연결 한 줄을 해제한다. Item 행 잠금(직렬화 지점) 뒤 junction을 current-read
     * 잠금 조회로 읽어 target 존재와 잔여 association을 원자적으로 판정한다 — 잠금 전 일반 읽기 junction
     * 조회는 두지 않는다(스냅숏 stale 행을 잠금 뒤 삭제하면 0-row DELETE로 500, 동시 커밋된 해제를 못 보면
     * 마지막 참조를 shared로 오판해 job 없는 orphan이 남는다). 미연결·비소유는 404 은닉이 non-PHOTO 거절보다
     * 우선이다. 마지막 참조였으면 기존 orphan 경로(PHOTO job 보존·손상분 즉시 삭제)를 같은 commit으로 묶는다.
     */
    @Transactional
    public DeletionResult detachEventItem(long userId, Long timelineEventId, Long timelineItemId) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        requireOwnedDraftRecord(userId, event.getDailyRecordId(), ExceptionType.TIMELINE_EVENT_NOT_FOUND);

        TimelineItem item = timelineItemService.findByIdForUpdate(timelineItemId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_ITEM_NOT_FOUND));
        List<TimelineEventItem> associations =
                timelineEventItemService.findByTimelineItemIdForUpdate(timelineItemId);
        TimelineEventItem target = associations.stream()
                .filter(link -> link.getTimelineEventId().equals(timelineEventId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_ITEM_NOT_FOUND));
        if (item.getItemType() != ItemType.PHOTO) {
            throw new BusinessException(ExceptionType.TIMELINE_ITEM_NOT_PHOTO);
        }

        if (associations.size() > 1) {
            timelineEventItemService.delete(target);
            return new DeletionResult(0, 1, 0);
        }
        OrphanPreparation preparation = prepareOrphanItems(List.of(item), userId);
        if (preparation.immediateDeleteItemIds().isEmpty()) {
            // valid PHOTO(job 보존) 또는 기존 job 재사용: Item은 남으므로 junction만 명시 해제한다.
            timelineEventItemService.delete(target);
        } else {
            // 손상 PHOTO: Item hard delete가 junction을 DB FK cascade로 지운다. 명시 junction delete를
            // 겹치면 즉시 실행되는 batch delete가 cascade로 행을 먼저 지워 commit flush가 0-row DELETE가 된다.
            timelineItemService.deleteByIds(preparation.immediateDeleteItemIds());
        }
        return new DeletionResult(preparation.scheduled(), 0, preparation.invalidSkipped());
    }

    /**
     * 삭제될 Event 집합에<b>만</b> 연결된 Item들(= 삭제 후 association 0이 될 orphan)을 계산한다.
     * 삭제 대상 밖 Event에도 연결된 Item은 shared로 보고 유지한다 — 정상 write 경로에선 same-record 규칙으로
     * cross-record 후보가 없어야 하지만, 있어도 방어적으로 유지된다.
     */
    private DeletionItems resolveDeletionItems(Set<Long> deletedEventIds) {
        List<Long> candidateItemIds = timelineEventItemService.findByTimelineEventIds(deletedEventIds).stream()
                .map(TimelineEventItem::getTimelineItemId)
                .distinct()
                .toList();
        Map<Long, Set<Long>> eventIdsByItemId = timelineEventItemService.findByTimelineItemIds(candidateItemIds)
                .stream()
                .collect(Collectors.groupingBy(TimelineEventItem::getTimelineItemId,
                        Collectors.mapping(TimelineEventItem::getTimelineEventId, Collectors.toSet())));
        Set<Long> orphanItemIds = candidateItemIds.stream()
                .filter(itemId -> deletedEventIds.containsAll(
                        eventIdsByItemId.getOrDefault(itemId, new HashSet<>())))
                .collect(Collectors.toSet());
        List<TimelineItem> candidateItems = timelineItemService.findByIds(candidateItemIds);
        int sharedPhotoCount = (int) candidateItems.stream()
                .filter(item -> item.getItemType() == ItemType.PHOTO)
                .filter(item -> !orphanItemIds.contains(item.getTimelineItemId()))
                .count();
        List<TimelineItem> orphanItems = candidateItems.stream()
                .filter(item -> orphanItemIds.contains(item.getTimelineItemId()))
                .toList();
        return new DeletionItems(orphanItems, sharedPhotoCount);
    }

    /**
     * valid orphan PHOTO는 job을 만들고 원문 Item을 보존한다.
     * non-PHOTO와 job을 만들 수 없는 손상 PHOTO만 request transaction에서 즉시 hard delete한다.
     */
    private OrphanPreparation prepareOrphanItems(List<TimelineItem> orphanItems, long userId) {
        int scheduled = 0;
        int invalidSkipped = 0;
        List<Long> immediateDeleteItemIds = new ArrayList<>();
        for (TimelineItem item : orphanItems) {
            if (item.getItemType() != ItemType.PHOTO) {
                immediateDeleteItemIds.add(item.getTimelineItemId());
                continue;
            }
            PhotoPayload photo;
            try {
                photo = objectMapper.treeToValue(item.getPayload(), PhotoPayload.class);
            } catch (JsonProcessingException exception) {
                invalidSkipped++;
                immediateDeleteItemIds.add(item.getTimelineItemId());
                log.warn("PHOTO payload 파싱 실패, 삭제 job 생략(orphan 허용): timelineItemId={} exceptionType={}",
                        item.getTimelineItemId(), exception.getClass().getSimpleName());
                continue;
            }
            if (photo == null || photo.filename() == null || photo.filename().isBlank()) {
                invalidSkipped++;
                immediateDeleteItemIds.add(item.getTimelineItemId());
                log.warn("PHOTO payload filename 없음, 삭제 job 생략(orphan 허용): timelineItemId={}",
                        item.getTimelineItemId());
                continue;
            }

            String objectKey = PhotoObjectKeys.fullKey(photo.filename(), userId);
            try {
                if (timelinePhotoDeleteJobService.insertIfAbsent(item.getTimelineItemId(), objectKey)) {
                    scheduled++;
                }
            } catch (IllegalArgumentException exception) {
                invalidSkipped++;
                immediateDeleteItemIds.add(item.getTimelineItemId());
                log.warn("PHOTO object key 저장 불가, 삭제 job 생략(orphan 허용): timelineItemId={} exceptionType={}",
                        item.getTimelineItemId(), exception.getClass().getSimpleName());
            }
        }
        return new OrphanPreparation(scheduled, invalidSkipped, List.copyOf(immediateDeleteItemIds));
    }

    /** record 없음·비소유는 {@code notFoundType}(404 은닉), SAVED는 409(-1003)로 거절한다. */
    private DailyRecord requireOwnedDraftRecord(long userId, Long dailyRecordId, ExceptionType notFoundType) {
        DailyRecord record = dailyRecordService.findById(dailyRecordId)
                .filter(owned -> owned.getUserId() == userId)
                .orElseThrow(() -> new BusinessException(notFoundType));
        if (record.getStatus() == DailyRecordStatus.SAVED) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
        }
        return record;
    }

    public record DeletionResult(int scheduled, int sharedRetained, int invalidSkipped) {
    }

    private record DeletionItems(List<TimelineItem> orphanItems, int sharedPhotoCount) {
    }

    private record OrphanPreparation(
            int scheduled,
            int invalidSkipped,
            List<Long> immediateDeleteItemIds) {
    }
}
