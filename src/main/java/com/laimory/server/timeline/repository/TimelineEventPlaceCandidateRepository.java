package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineEventPlaceCandidate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** timeline_event_place_candidates 레포. 조회 순서 = 우선순위 = id 오름차순이다. */
public interface TimelineEventPlaceCandidateRepository extends JpaRepository<TimelineEventPlaceCandidate, Long> {

    List<TimelineEventPlaceCandidate> findByTimelineEventIdInOrderByTimelineEventPlaceCandidateIdAsc(
            Collection<Long> timelineEventIds);

    /** Event soft delete와 같은 transaction에서 그 Event의 후보를 지운다. */
    @Modifying
    @Query("delete from TimelineEventPlaceCandidate c where c.timelineEventId = :timelineEventId")
    int deleteByTimelineEventId(@Param("timelineEventId") Long timelineEventId);

    /** DailyRecord soft delete와 같은 transaction에서 그 record 소속 Event 전부의 후보를 지운다. */
    @Modifying
    @Query(value = "delete c from timeline_event_place_candidates c "
            + "join timeline_events e on e.timeline_event_id = c.timeline_event_id "
            + "where e.daily_record_id = :dailyRecordId",
            nativeQuery = true)
    int deleteByDailyRecordId(@Param("dailyRecordId") Long dailyRecordId);
}
