package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** timeline_events leaf 서비스. 자신과 1:1인 TimelineEventRepository에만 접근한다. 조회는 활성 Event만 본다. */
@Service
@RequiredArgsConstructor
public class TimelineEventService {

    private final TimelineEventRepository timelineEventRepository;

    public TimelineEvent save(TimelineEvent event) {
        return timelineEventRepository.save(event);
    }

    public Optional<TimelineEvent> findById(Long timelineEventId) {
        return timelineEventRepository.findByTimelineEventIdAndDeletedAtIsNull(timelineEventId);
    }

    /** 해당 일자 이벤트를 start_at, timeline_event_id 오름차순으로 반환(표시 순서 고정). */
    public List<TimelineEvent> findByDailyRecordId(Long dailyRecordId) {
        return timelineEventRepository.findByDailyRecordIdAndDeletedAtIsNullOrderByStartAtAscTimelineEventIdAsc(dailyRecordId);
    }

    /** 여러 일일 기록의 이벤트를 record, start_at, event ID 오름차순으로 반환한다. */
    public List<TimelineEvent> findByDailyRecordIds(Collection<Long> dailyRecordIds) {
        if (dailyRecordIds.isEmpty()) {
            return List.of();
        }
        return timelineEventRepository
                .findByDailyRecordIdInAndDeletedAtIsNullOrderByDailyRecordIdAscStartAtAscTimelineEventIdAsc(dailyRecordIds);
    }

    /**
     * 활성 Event를 {@code now} 시각으로 soft delete하고 장소명·주소·좌표를 지운다. junction·Item은 건드리지 않는다.
     * 실제로 삭제 표시한 행 수를 반환한다(0 = 이미 삭제됨).
     */
    public int softDelete(Long timelineEventId, LocalDateTime now) {
        return timelineEventRepository.softDelete(timelineEventId, now);
    }

    /** 삭제되는 record의 활성 Event 전부를 같은 {@code now}로 soft delete하고 장소명·주소·좌표를 지운다. */
    public int softDeleteByDailyRecordId(Long dailyRecordId, LocalDateTime now) {
        return timelineEventRepository.softDeleteByDailyRecordId(dailyRecordId, now);
    }
}
