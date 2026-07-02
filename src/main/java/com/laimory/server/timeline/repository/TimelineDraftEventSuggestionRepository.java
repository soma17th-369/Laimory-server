package com.laimory.server.timeline.repository;

import com.laimory.server.timeline.entity.TimelineDraftEventSuggestion;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * timeline_draft_event_suggestions 레포. task_id 단위 조회/삭제, 보관기간 초과 행 bulk 삭제(cleanup용).
 *
 * <p>로드 정렬은 두지 않는다 — 하루 타임라인은 선형 시퀀스라 이벤트 start_at이 유일(도메인 불변식)이고,
 * 최종 읽기의 start_at 정렬만으로 순서가 결정적이라 eventRows 로드 순서는 결과에 영향이 없다.
 */
public interface TimelineDraftEventSuggestionRepository extends JpaRepository<TimelineDraftEventSuggestion, Long> {

    List<TimelineDraftEventSuggestion> findByTaskId(String taskId);

    @Modifying
    @Transactional
    void deleteByTaskId(String taskId);

    /**
     * 보관기간 초과(created_at &lt; cutoff) 행을 단일 bulk DELETE로 정리하고 삭제 건수를 반환한다(cleanup 스케줄러용).
     * source item과 달리 S3 객체가 없어 행별 처리가 불필요하므로 select+행별 삭제 대신 한 문장으로 지운다.
     */
    @Modifying
    @Transactional
    @Query("delete from TimelineDraftEventSuggestion e where e.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
