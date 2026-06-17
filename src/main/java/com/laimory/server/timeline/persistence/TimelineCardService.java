package com.laimory.server.timeline.persistence;

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

    public List<TimelineCard> findByDailyRecordId(Long dailyRecordId) {
        return timelineCardRepository.findByDailyRecordId(dailyRecordId);
    }
}
