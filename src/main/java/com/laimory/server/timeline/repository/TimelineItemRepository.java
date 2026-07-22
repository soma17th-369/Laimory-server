package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimelineItemRepository extends JpaRepository<TimelineItem, Long> {

    // append 시 이미 저장된 source item을 rawId로 제외하기 위한 projection 조회.
    // rawId만 select 한다(JSON payload를 든 전체 엔티티 로드 회피). 후보 itemIds·rawIds로 좁혀 전체 스캔을 막는다.
    @Query("select ti.rawId from TimelineItem ti where ti.timelineItemId in :itemIds and ti.rawId in :rawIds")
    List<String> findRawIdsByTimelineItemIdInAndRawIdIn(@Param("itemIds") Collection<Long> itemIds,
                                                        @Param("rawIds") Collection<String> rawIds);
}
