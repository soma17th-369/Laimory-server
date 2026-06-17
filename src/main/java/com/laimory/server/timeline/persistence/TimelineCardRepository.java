package com.laimory.server.timeline.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineCardRepository extends JpaRepository<TimelineCard, Long> {

    List<TimelineCard> findByDailyRecordId(Long dailyRecordId);
}
