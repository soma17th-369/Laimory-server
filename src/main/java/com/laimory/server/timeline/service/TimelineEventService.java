package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** timeline_events leaf 서비스. 자신과 1:1인 TimelineEventRepository에만 접근한다. */
@Service
@RequiredArgsConstructor
public class TimelineEventService {

    private final TimelineEventRepository timelineEventRepository;

    public TimelineEvent save(TimelineEvent event) {
        return timelineEventRepository.save(event);
    }

    public Optional<TimelineEvent> findById(Long timelineEventId) {
        return timelineEventRepository.findById(timelineEventId);
    }

    /** 해당 일자 이벤트를 start_at, timeline_event_id 오름차순으로 반환(표시 순서 고정). */
    public List<TimelineEvent> findByDailyRecordId(Long dailyRecordId) {
        return timelineEventRepository.findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(dailyRecordId);
    }

    /** 이벤트 행을 삭제한다. 하위 timeline_items는 DB FK {@code ON DELETE CASCADE}가 지운다(JPA cascade 없음). */
    public void deleteById(Long timelineEventId) {
        timelineEventRepository.deleteById(timelineEventId);
    }
}
