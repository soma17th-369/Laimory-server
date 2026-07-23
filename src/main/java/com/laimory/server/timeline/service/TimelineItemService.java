package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** timeline_items leaf 서비스. 자신과 1:1인 TimelineItemRepository에만 접근한다(Event 연결은 junction leaf 소유). */
@Service
@RequiredArgsConstructor
public class TimelineItemService {

    private final TimelineItemRepository timelineItemRepository;

    public TimelineItem save(TimelineItem item) {
        return timelineItemRepository.save(item);
    }

    /** ID 목록으로 Item을 로드한다(정렬 미보장 — 표시 순서는 호출부가 조립). 빈 입력이면 빈 목록. */
    public List<TimelineItem> findByIds(Collection<Long> timelineItemIds) {
        if (timelineItemIds.isEmpty()) {
            return List.of();
        }
        return timelineItemRepository.findAllById(timelineItemIds);
    }

    /**
     * 후보 Item ID들 중 rawId가 후보 rawIds에 속하는 것들의 rawId 집합을 반환한다.
     * append 시 이미 타임라인에 반영된 source item을 rawId로 제외하는 데 쓴다(Item ID 축은 junction 조회가 공급).
     * itemIds 또는 rawIds가 비면 빈 집합을 반환한다(불필요한 빈 IN 쿼리 회피).
     */
    public Set<String> findSavedRawIds(Collection<Long> itemIds, Collection<String> rawIds) {
        if (itemIds.isEmpty() || rawIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(timelineItemRepository.findRawIdsByTimelineItemIdInAndRawIdIn(itemIds, rawIds));
    }

    /** 후보 Item ID와 rawId가 모두 일치하는 final Item을 반환한다(PHOTO append 분류용). */
    public List<TimelineItem> findByIdsAndRawIds(Collection<Long> itemIds, Collection<String> rawIds) {
        if (itemIds.isEmpty() || rawIds.isEmpty()) {
            return List.of();
        }
        return timelineItemRepository.findByTimelineItemIdInAndRawIdIn(itemIds, rawIds);
    }

    /** Item 행들을 삭제한다(association 0 orphan 정리 전용 — 자기 junction 행은 DB FK cascade가 지운다). */
    public void deleteByIds(Collection<Long> timelineItemIds) {
        if (timelineItemIds.isEmpty()) {
            return;
        }
        timelineItemRepository.deleteAllByIdInBatch(timelineItemIds);
    }
}
