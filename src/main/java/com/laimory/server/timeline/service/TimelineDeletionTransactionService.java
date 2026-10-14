package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 타임라인 삭제의 DB 트랜잭션 경계 전담 빈. {@link TimelineDeletionService}가 Spring 프록시를 통해
 * 호출한다 — 오케스트레이터 안에
 * {@code @Transactional} 메서드를 두면 self-invocation으로 트랜잭션이 조용히 무효화되므로 분리한다.
 *
 * <p>Event·DailyRecord 삭제는 soft delete다(#568). 짧은 트랜잭션 안에서 소유권을 <b>재확인</b>한 뒤
 * {@code deleted_at}만 찍고 감정·장소명·주소를 지운다. 행·junction·Item은 분석용으로 남는다 — Item 정리는
 * 이 트랜잭션의 책임이 아니다. 삭제 표시는 조건부 bulk UPDATE라 동시 삭제의 후발 요청은
 * 0행으로 끝나고(응답은 성공) 첫 삭제 시각이 남는다.
 *
 * <p>Event-Item 연결 해제는 junction만 hard delete한다. 연결을 잃은 Item과 그 S3 사진은 orphan 스위퍼가
 * 정리한다(#551) — 이 transaction은 S3를 호출하지도, Item을 분류하지도 않는다.
 */
@Service
@RequiredArgsConstructor
public class TimelineDeletionTransactionService {

    private final TimelineEventService timelineEventService;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;
    private final DailyRecordService dailyRecordService;
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
     * 은닉이 non-PHOTO 거절보다 우선이다. Item은 지우지 않는다 — 마지막 연결이었으면 고아로 남고,
     * S3 사진과 Item 정리는 orphan 스위퍼가 맡는다(#551).
     */
    @Transactional
    public void detachEventItem(UUID subjectId, Long timelineEventId, Long timelineItemId) {
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
    }

    /** record 없음·비소유는 {@code notFoundType}(404 은닉)으로 거절한다. */
    private void requireOwnedRecord(UUID subjectId, Long dailyRecordId, ExceptionType notFoundType) {
        dailyRecordService.findById(dailyRecordId)
                .filter(owned -> owned.getSubjectId().equals(subjectId))
                .orElseThrow(() -> new BusinessException(notFoundType));
    }
}
