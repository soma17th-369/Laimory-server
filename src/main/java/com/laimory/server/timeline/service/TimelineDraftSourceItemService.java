package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** timeline_draft_source_items leaf 서비스. 자신과 1:1인 TimelineDraftSourceItemRepository에만 접근한다. */
@Service
@RequiredArgsConstructor
public class TimelineDraftSourceItemService {

    private final TimelineDraftSourceItemRepository timelineDraftSourceItemRepository;

    public List<TimelineDraftSourceItem> saveAll(List<TimelineDraftSourceItem> items) {
        return timelineDraftSourceItemRepository.saveAll(items);
    }

    public List<TimelineDraftSourceItem> findByTaskId(String taskId) {
        return timelineDraftSourceItemRepository.findByTaskId(taskId);
    }

    public void deleteByTaskId(String taskId) {
        timelineDraftSourceItemRepository.deleteByTaskId(taskId);
    }

    /** 보관기간 초과(created_at < cutoff) draft 행을 조회한다(cleanup 스케줄러가 행별 S3 삭제 후 개별 삭제). */
    public List<TimelineDraftSourceItem> findCreatedBefore(LocalDateTime cutoff) {
        return timelineDraftSourceItemRepository.findByCreatedAtBefore(cutoff);
    }

    /** draft 행 하나를 PK로 삭제한다(cleanup이 S3 객체 삭제 성공한 행만 지울 때 사용). */
    public void deleteById(Long id) {
        timelineDraftSourceItemRepository.deleteById(id);
    }
}
