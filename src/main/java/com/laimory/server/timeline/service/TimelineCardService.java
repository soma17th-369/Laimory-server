package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineCard;
import com.laimory.server.timeline.repository.TimelineCardRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** timeline_cards leaf 서비스. 자신과 1:1인 TimelineCardRepository에만 접근한다. */
@Service
@RequiredArgsConstructor
public class TimelineCardService {

    private final TimelineCardRepository timelineCardRepository;

    public TimelineCard save(TimelineCard card) {
        return timelineCardRepository.save(card);
    }

    /** 해당 일자 카드를 start_at, id 오름차순으로 반환(표시 순서 고정). */
    public List<TimelineCard> findByDailyRecordId(Long dailyRecordId) {
        return timelineCardRepository.findByDailyRecordIdOrderByStartAtAscIdAsc(dailyRecordId);
    }
}
