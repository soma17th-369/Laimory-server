package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.DailyRecord;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyRecordRepository extends JpaRepository<DailyRecord, Long> {

    Optional<DailyRecord> findByUserIdAndRecordDate(Long userId, LocalDate recordDate);
}
