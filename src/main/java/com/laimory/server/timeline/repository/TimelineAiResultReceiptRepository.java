package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineAiResultReceipt;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/** timeline_ai_result_receipts 레포. task 단위 존재 확인과 보관기간 초과 행 정리(cleanup)만 한다. */
public interface TimelineAiResultReceiptRepository extends JpaRepository<TimelineAiResultReceipt, String> {

    /** 보관기간 초과(created_at &lt; cutoff) 영수증을 삭제하고 삭제 행 수를 반환한다(cleanup 스케줄러용). */
    @Modifying
    @Transactional
    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
