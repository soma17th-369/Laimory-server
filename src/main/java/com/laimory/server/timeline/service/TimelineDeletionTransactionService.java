package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * <p>짧은 트랜잭션 안에서 소유권을 <b>재확인</b>하고, 삭제 후 association이 0이 될 PHOTO Item의
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
    private final UserMemoryUpdatePendingStore userMemoryUpdatePendingStore;
    private final ObjectMapper objectMapper;

    /** 소유권 재확인 후 PHOTO Item/job 보존과 Event/non-PHOTO orphan hard delete를 한 commit으로 묶는다. */
    @Transactional
    public DeletionResult deleteEvent(UUID subjectId, Long timelineEventId) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        requireOwnedRecord(subjectId, event.getDailyRecordId(), ExceptionType.TIMELINE_EVENT_NOT_FOUND);

        DeletionItems deletionItems = resolveDeletionItems(Set.of(timelineEventId));
        OrphanPreparation preparation = prepareOrphanItems(deletionItems.orphanItems(), subjectId);
        timelineEventService.deleteById(timelineEventId);
        timelineItemService.deleteByIds(preparation.immediateDeleteItemIds());
        return new DeletionResult(
                preparation.scheduled(), deletionItems.sharedPhotoCount(), preparation.invalidSkipped());
    }

    /** 소유권 재확인 후 PHOTO Item/job 보존과 DailyRecord/non-PHOTO orphan hard delete를 한 commit으로 묶는다. */
    @Transactional
    public DeletionResult deleteDailyRecord(UUID subjectId, Long dailyRecordId) {
        requireOwnedRecord(subjectId, dailyRecordId, ExceptionType.DAILY_RECORD_NOT_FOUND);

        Set<Long> recordEventIds = timelineEventService.findByDailyRecordId(dailyRecordId).stream()
                .map(TimelineEvent::getTimelineEventId)
                .collect(Collectors.toSet());
        DeletionItems deletionItems = resolveDeletionItems(recordEventIds);
        OrphanPreparation preparation = prepareOrphanItems(deletionItems.orphanItems(), subjectId);
        dailyRecordService.deleteById(dailyRecordId);
        timelineItemService.deleteByIds(preparation.immediateDeleteItemIds());
        // 갱신할 재료가 사라졌으므로 미반영 큐에서도 뺀다. 남겨 두면 그 member는 record id를 어디서도
        // 얻을 수 없어 id 기반 정리로는 영영 안 잡히고, 다음 일일 배치가 재료 없음으로 걷어낼 때까지
        // 큐에 남는다(계정 삭제의 완료 시점 zero-check를 흐리는 유일한 잔여였다).
        userMemoryUpdatePendingStore.removeAll(subjectId, List.of(dailyRecordId));
        return new DeletionResult(
                preparation.scheduled(), deletionItems.sharedPhotoCount(), preparation.invalidSkipped());
    }

    /**
     * Event에서 PHOTO Item 연결 한 줄을 해제한다. junction 삭제는 영향 행 수를 반환하는 직접 DELETE라
     * 같은 junction 동시 해제의 후발 요청은 stale-state 500 없이 404로 수렴한다. 미연결·비소유는 404
     * 은닉이 non-PHOTO 거절보다 우선이다. 잔여 association 판정은 자기 삭제를 반영한 일반 읽기
     * best-effort다 — 서로 다른 junction의 동시 해제가 겹치면 마지막 참조를 shared로 오판해 job 없는
     * orphan Item이 남을 수 있고, 이 수렴은 orphan 스위퍼(후속)가 맡는다. 마지막 참조의 orphan 처리
     * (유효 PHOTO job 보존·손상 PHOTO 즉시 삭제)는 root 삭제와 같은 규칙을 같은 commit으로 묶는다.
     */
    @Transactional
    public DeletionResult detachEventItem(UUID subjectId, Long timelineEventId, Long timelineItemId) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        requireOwnedRecord(subjectId, event.getDailyRecordId(), ExceptionType.TIMELINE_EVENT_NOT_FOUND);

        TimelineItem item = timelineItemService.findById(timelineItemId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_ITEM_NOT_FOUND));
        if (!timelineEventItemService.isLinked(timelineEventId, timelineItemId)) {
            throw new BusinessException(ExceptionType.TIMELINE_ITEM_NOT_FOUND);
        }
        if (item.getItemType() != ItemType.PHOTO) {
            throw new BusinessException(ExceptionType.TIMELINE_ITEM_NOT_PHOTO);
        }

        if (timelineEventItemService.deleteLink(timelineEventId, timelineItemId) == 0) {
            throw new BusinessException(ExceptionType.TIMELINE_ITEM_NOT_FOUND);
        }
        if (!timelineEventItemService.findByTimelineItemIds(List.of(timelineItemId)).isEmpty()) {
            return new DeletionResult(0, 1, 0);
        }
        OrphanPreparation preparation = prepareOrphanItems(List.of(item), subjectId);
        timelineItemService.deleteByIds(preparation.immediateDeleteItemIds());
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
    private OrphanPreparation prepareOrphanItems(List<TimelineItem> orphanItems, UUID subjectId) {
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

            String objectKey = PhotoObjectKeys.subjectFullKey(photo.filename(), subjectId);
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

    /** record 없음·비소유는 {@code notFoundType}(404 은닉)으로 거절한다. */
    private void requireOwnedRecord(UUID subjectId, Long dailyRecordId, ExceptionType notFoundType) {
        dailyRecordService.findById(dailyRecordId)
                .filter(owned -> owned.getSubjectId().equals(subjectId))
                .orElseThrow(() -> new BusinessException(notFoundType));
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
