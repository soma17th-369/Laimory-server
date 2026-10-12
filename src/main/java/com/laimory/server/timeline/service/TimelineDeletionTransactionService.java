package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 타임라인 삭제의 DB 트랜잭션 경계 전담 빈. {@link TimelineDeletionService}가 Spring 프록시를 통해
 * 호출한다 — 오케스트레이터 안에
 * {@code @Transactional} 메서드를 두면 self-invocation으로 트랜잭션이 조용히 무효화되므로 분리한다.
 *
 * <p>Event·DailyRecord 삭제는 soft delete다(#568). 짧은 트랜잭션 안에서 소유권을 <b>재확인</b>한 뒤
 * {@code deleted_at}만 찍고 감정·장소명·주소를 지운다. 행·junction·Item은 분석용으로 남고 PHOTO 삭제 job도 만들지
 * 않는다 — Item 정리는 이 트랜잭션의 책임이 아니다. 삭제 표시는 조건부 bulk UPDATE라 동시 삭제의 후발 요청은
 * 0행으로 끝나고(응답은 성공) 첫 삭제 시각이 남는다.
 *
 * <p>Event-Item 연결 해제는 지금처럼 junction을 hard delete하고, 마지막 참조였던 PHOTO Item은 S3 삭제 job과 함께
 * 보존한다. S3 호출과 PHOTO Item 최종 hard delete는 이 transaction 밖의 worker/completion transaction만 수행한다.
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
    private final Clock clock;

    /** 소유권 재확인 후 Event를 soft delete하고 장소명·주소를 지운다. */
    @Transactional
    public void deleteEvent(UUID subjectId, Long timelineEventId) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        requireOwnedRecord(subjectId, event.getDailyRecordId(), ExceptionType.TIMELINE_EVENT_NOT_FOUND);

        timelineEventService.softDelete(timelineEventId, LocalDateTime.now(clock));
    }

    /**
     * 소유권 재확인 후 DailyRecord와 그 활성 Event를 같은 시각으로 soft delete한다. record의 감정, Event의
     * 장소명·주소를 지운다. 먼저 개별 삭제된 Event는 원래 시각을 유지한다. record가 이미 삭제됐으면(동시 삭제가
     * 먼저 commit) Event 처리도 건너뛴다 — 먼저 끝난 삭제가 같은 일을 했다.
     */
    @Transactional
    public void deleteDailyRecord(UUID subjectId, Long dailyRecordId) {
        requireOwnedRecord(subjectId, dailyRecordId, ExceptionType.DAILY_RECORD_NOT_FOUND);

        LocalDateTime now = LocalDateTime.now(clock);
        if (dailyRecordService.softDelete(dailyRecordId, now) == 0) {
            return;
        }
        timelineEventService.softDeleteByDailyRecordId(dailyRecordId, now);
        // 미반영 큐 정리는 여기서 하지 않는다 — Redis는 이 transaction에 참여하지 않으므로, 여기서
        // 지우고 commit이 실패하면 record는 되살아나는데 큐 member만 사라져 그 하루가 User Memory에
        // 반영될 재시도 근거를 잃는다. 정리는 commit 이후 TimelineDeletionService가 한다.
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

    private record OrphanPreparation(
            int scheduled,
            int invalidSkipped,
            List<Long> immediateDeleteItemIds) {
    }
}
