package com.laimory.server.timeline.service;

import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 삭제(#302)의 타임라인 콘텐츠 graph 제거. 소유 판정과 삭제가 한 곳에 있어야 하므로 timeline
 * 도메인이 소유하고, 계정 삭제 orchestration이 이 서비스를 합성한다.
 *
 * <p><b>왜 배치가 transaction 경계인가</b>: {@code timeline_items}에는 {@code subject_id}도
 * {@code daily_record_id}도 없고 소유는 junction graph로만 해석한다. 그런데 record를 지우면 Event가
 * CASCADE되고 junction도 함께 사라진다 — record를 먼저 지우고 Item을 나중에 지우면 <b>그 사이의 crash가
 * Item을 영구히 특정 불가능하게 만든다</b>(다음 실행의 snapshot은 record가 없어 빈 집합이다).
 * 그래서 "조회 → 삭제"를 배치 단위 한 transaction에 묶는다. 배치 크기가 상한이라 transaction은 짧다.
 *
 * <p><b>fail-closed</b>: snapshot한 Item이 다른 subject의 Event에도 연결된 손상 상태면 조용히 지우지
 * 않고 {@link CrossSubjectItemException}을 던진다. 남의 데이터를 지우는 것보다 멈추는 편이 낫다.
 */
@Service
@RequiredArgsConstructor
public class TimelineContentErasureService {

    private final DailyRecordRepository dailyRecordRepository;
    private final TimelineEventItemRepository timelineEventItemRepository;
    private final TimelineItemRepository timelineItemRepository;
    private final TimelineDraftSourceItemRepository timelineDraftSourceItemRepository;

    /** snapshot한 Item이 다른 subject 소유로 확인됐을 때 — 자동 삭제를 멈추고 수동 확인으로 보낸다. */
    public static class CrossSubjectItemException extends IllegalStateException {
        public CrossSubjectItemException() {
            super("timeline item is owned by another subject");
        }
    }

    /**
     * subject의 record 한 batch와 거기 연결된 Item을 <b>같은 transaction</b>에서 지운다.
     * Event와 junction은 FK CASCADE로 함께 사라진다.
     *
     * @return 처리한 record 수(0 = 더 없음)
     */
    @Transactional
    public int deleteRecordBatch(UUID subjectId, int batchSize) {
        List<Long> recordIds = dailyRecordRepository.findIdsBySubjectIdAfterId(
                subjectId, 0L, org.springframework.data.domain.PageRequest.of(0, batchSize));
        if (recordIds.isEmpty()) {
            return 0;
        }
        List<Long> itemIds = timelineEventItemRepository.findItemIdsByDailyRecordIdIn(recordIds);
        if (!itemIds.isEmpty()) {
            requireSoleOwner(subjectId, itemIds);
            // Item을 먼저 지운다 — record가 먼저 사라지면 junction도 함께 사라져 이 id들을 다시 얻을 수 없다.
            timelineItemRepository.deleteAllByIdIn(itemIds);
        }
        return dailyRecordRepository.deleteAllByIdIn(recordIds);
    }

    /** AI 입력 staging 한 batch. subject FK가 {@code RESTRICT}라 mapping 삭제 전에 0이 되어야 한다. */
    public int deleteDraftSourceBatch(UUID subjectId, int batchSize) {
        return timelineDraftSourceItemRepository.deleteBySubjectId(subjectId.toString(), batchSize);
    }

    /**
     * 이 Item들의 현재 소유자가 대상 subject뿐인지 확인한다.
     */
    private void requireSoleOwner(UUID subjectId, List<Long> itemIds) {
        Set<String> owners = timelineEventItemRepository.findOwnerSubjectIdsByItemIdIn(itemIds).stream()
                .collect(Collectors.toSet());
        if (owners.stream().anyMatch(owner -> !subjectId.toString().equals(owner))) {
            throw new CrossSubjectItemException();
        }
    }
}
