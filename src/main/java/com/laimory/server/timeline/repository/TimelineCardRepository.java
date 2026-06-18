package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineCard;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineCardRepository extends JpaRepository<TimelineCard, Long> {

    // 표시 순서 고정(DB 반환순 의존 제거): start_at, id 오름차순
    List<TimelineCard> findByDailyRecordIdOrderByStartAtAscIdAsc(Long dailyRecordId);
}
