package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineEventItemId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** timeline_event_items(junction) 레포. 조회는 event/item ID 축 양방향, 삭제는 DB FK cascade가 담당한다. */
public interface TimelineEventItemRepository extends JpaRepository<TimelineEventItem, TimelineEventItemId> {

    List<TimelineEventItem> findByTimelineEventId(Long timelineEventId);

    List<TimelineEventItem> findByTimelineEventIdIn(Collection<Long> timelineEventIds);

    /** 후보 Item들의 현재 association 전체 — 삭제 흐름의 exclusive/shared 판정과 orphan 검출에 쓴다. */
    List<TimelineEventItem> findByTimelineItemIdIn(Collection<Long> timelineItemIds);
}
