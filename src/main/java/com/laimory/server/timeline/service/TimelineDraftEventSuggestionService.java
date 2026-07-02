package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineDraftEventSuggestion;
import com.laimory.server.timeline.repository.TimelineDraftEventSuggestionRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * timeline_draft_event_suggestions leaf 서비스. 자신과 1:1인 TimelineDraftEventSuggestionRepository에만 접근한다.
 *
 * <p>이 테이블은 AI가 write하고 API는 read(finalize)·delete(finalize/cleanup)만 하므로 저장 메서드는 없다
 * (테스트 픽스처는 레포의 save를 직접 쓴다).
 */
@Service
@RequiredArgsConstructor
public class TimelineDraftEventSuggestionService {

    private final TimelineDraftEventSuggestionRepository timelineDraftEventSuggestionRepository;

    public List<TimelineDraftEventSuggestion> findByTaskId(String taskId) {
        return timelineDraftEventSuggestionRepository.findByTaskId(taskId);
    }

    public void deleteByTaskId(String taskId) {
        timelineDraftEventSuggestionRepository.deleteByTaskId(taskId);
    }

    /** 보관기간 초과(created_at < cutoff) 제안 행을 단일 bulk DELETE로 정리하고 삭제 건수를 반환한다(cleanup 스케줄러용). */
    public int deleteCreatedBefore(LocalDateTime cutoff) {
        return timelineDraftEventSuggestionRepository.deleteCreatedBefore(cutoff);
    }
}
