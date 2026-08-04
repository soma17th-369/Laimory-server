package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** timeline_event_items(junction) leaf 서비스. 자신과 1:1인 TimelineEventItemRepository에만 접근한다. */
@Service
@RequiredArgsConstructor
public class TimelineEventItemService {

    private final TimelineEventItemRepository timelineEventItemRepository;

    public List<TimelineEventItem> saveAll(List<TimelineEventItem> links) {
        return timelineEventItemRepository.saveAll(links);
    }

    public List<TimelineEventItem> findByTimelineEventId(Long timelineEventId) {
        return timelineEventItemRepository.findByTimelineEventId(timelineEventId);
    }

    /** 빈 입력이면 빈 목록(불필요한 빈 IN 쿼리 회피) — 이하 IN 조회 공통. */
    public List<TimelineEventItem> findByTimelineEventIds(Collection<Long> timelineEventIds) {
        if (timelineEventIds.isEmpty()) {
            return List.of();
        }
        return timelineEventItemRepository.findByTimelineEventIdIn(timelineEventIds);
    }

    /** 후보 Item들의 현재 association 전체 — 삭제 흐름의 exclusive/shared 판정과 orphan 검출에 쓴다. */
    public List<TimelineEventItem> findByTimelineItemIds(Collection<Long> timelineItemIds) {
        if (timelineItemIds.isEmpty()) {
            return List.of();
        }
        return timelineEventItemRepository.findByTimelineItemIdIn(timelineItemIds);
    }

    /**
     * 연결 해제 트랜잭션의 current-read 잠금 조회 — Item 행 잠금 아래에서 target junction 존재와
     * 잔여 association 판정의 단일 권위로 쓴다.
     */
    public List<TimelineEventItem> findByTimelineItemIdForUpdate(Long timelineItemId) {
        return timelineEventItemRepository.findByTimelineItemIdForUpdate(timelineItemId);
    }

    /** 잠금 조회가 반환한 managed 행 삭제(연결 해제 전용 — root 삭제의 junction 정리는 DB FK cascade 담당). */
    public void delete(TimelineEventItem link) {
        timelineEventItemRepository.delete(link);
    }
}
