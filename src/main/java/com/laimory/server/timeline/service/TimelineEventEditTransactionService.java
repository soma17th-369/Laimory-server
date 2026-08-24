package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event PATCH의 단일 DB transaction writer. outer edit service가 사전 검증을 끝낸 뒤 이 별도 Spring bean을
 * 호출하므로, 메서드 반환 전에 Event·memo·Item·junction commit이 완료된다.
 * 사진 분류({@code resolve})·저장({@code link})은 수동 Event 생성과 공유하는
 * {@link TimelineEventPhotoAddService}가 이 transaction에 합류해 수행한다.
 */
@Service
@RequiredArgsConstructor
public class TimelineEventEditTransactionService {

    private final TimelineEventService timelineEventService;
    private final DailyRecordService dailyRecordService;
    private final TimelineEventPhotoAddService timelineEventPhotoAddService;

    /** 소유권을 재확인하고 Event 필드와 수동 PHOTO graph를 원자적으로 반영한다. */
    @Transactional
    public void updateEvent(UUID subjectId, Long timelineEventId, TimelineEventEditCommand command) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        DailyRecord record = requireOwnedRecord(subjectId, event.getDailyRecordId());

        // 분류와 모든 DB-dependent 검증을 entity mutation보다 먼저 끝내 validation 실패 시 Event/memo도 그대로 둔다.
        TimelineEventPhotoAddService.PhotoChanges photoChanges =
                timelineEventPhotoAddService.resolve(record, timelineEventId, command.photosToAdd());

        TimelineEventType targetEventType = command.eventType() != null
                ? command.eventType() : event.getEventType();
        event.updateDetails(targetEventType, command.title(), command.subtitle(), command.startAt(), command.endAt());
        if (command.memoPresent()) {
            event.updateMemo(command.memo());
        }

        timelineEventPhotoAddService.link(subjectId, timelineEventId, photoChanges);
    }

    private DailyRecord requireOwnedRecord(UUID subjectId, Long dailyRecordId) {
        return dailyRecordService.findById(dailyRecordId)
                .filter(owned -> owned.getSubjectId().equals(subjectId))
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
    }
}
