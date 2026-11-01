package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineEventPlaceCandidate;
import com.laimory.server.timeline.repository.TimelineEventPlaceCandidateRepository;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** timeline_event_place_candidates leaf 서비스. 자신과 1:1인 TimelineEventPlaceCandidateRepository에만 접근한다. */
@Service
@RequiredArgsConstructor
public class TimelineEventPlaceCandidateService {

    private final TimelineEventPlaceCandidateRepository timelineEventPlaceCandidateRepository;

    /** 목록 순서대로 INSERT한다 — id 순서가 곧 우선순위다. */
    public List<TimelineEventPlaceCandidate> saveAll(List<TimelineEventPlaceCandidate> candidates) {
        return timelineEventPlaceCandidateRepository.saveAll(candidates);
    }

    /** 여러 Event의 후보를 우선순위(id) 순서로 한 번에 읽는다. 빈 입력이면 빈 목록(빈 IN 쿼리 회피). */
    public List<TimelineEventPlaceCandidate> findByTimelineEventIds(Collection<Long> timelineEventIds) {
        if (timelineEventIds.isEmpty()) {
            return List.of();
        }
        return timelineEventPlaceCandidateRepository
                .findByTimelineEventIdInOrderByTimelineEventPlaceCandidateIdAsc(timelineEventIds);
    }

    public int deleteByTimelineEventId(Long timelineEventId) {
        return timelineEventPlaceCandidateRepository.deleteByTimelineEventId(timelineEventId);
    }

    public int deleteByDailyRecordId(Long dailyRecordId) {
        return timelineEventPlaceCandidateRepository.deleteByDailyRecordId(dailyRecordId);
    }
}
