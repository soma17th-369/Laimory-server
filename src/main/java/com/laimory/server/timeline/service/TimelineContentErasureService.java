package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
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
    private final TimelinePhotoDeleteJobRepository timelinePhotoDeleteJobRepository;
    private final TimelineDraftSourceItemRepository timelineDraftSourceItemRepository;

    /** snapshot한 Item이 다른 subject 소유로 확인됐을 때 — 자동 삭제를 멈추고 수동 확인으로 보낸다. */
    public static class CrossSubjectItemException extends IllegalStateException {
        public CrossSubjectItemException() {
            super("timeline item is owned by another subject");
        }
    }

    /**
     * PHOTO delete job과 그 원문 Item을 한 batch 지운다.
     *
     * <p>job이 존재하는 Item은 마지막 Event 참조가 사라져 생긴 것이라 <b>junction 0이고 record graph
     * snapshot에 잡히지 않는다</b>. job 행만 지우고 Item을 두면 그 Item은 아무도 정리하지 않으므로 둘을
     * 함께 지운다. job 행을 지운 뒤 crash해도 Item id를 잃지 않도록 <b>같은 transaction</b>이다.
     *
     * <p>record graph 삭제보다 <b>먼저</b> 호출해야 한다 — Item FK가 {@code RESTRICT}라 job 행이 남아
     * 있으면 그 Item 삭제가 거절된다.
     *
     * @return 처리한 job 수(0 = 더 없음)
     */
    @Transactional
    public int deletePhotoDeleteJobBatch(UUID subjectId, int batchSize) {
        String namespacePrefix = com.laimory.server.timeline.photo.PhotoObjectKeys.subjectNamespace(subjectId)
                + "/photos/";
        List<TimelinePhotoDeleteJob> jobs =
                timelinePhotoDeleteJobRepository.findByObjectKeyNamespace(namespacePrefix, batchSize);
        if (jobs.isEmpty()) {
            return 0;
        }
        List<Long> itemIds = jobs.stream()
                .map(TimelinePhotoDeleteJob::getTimelineItemId)
                .distinct()
                .toList();
        // object_key prefix는 "발급 당시 subject"일 뿐 현재 소유의 증거가 아니다. 재연결된 Item이 다른
        // subject의 Event에 걸려 있으면 지우지 않는다(기존 worker도 S3 직전 association을 재검증한다).
        requireSoleOwner(subjectId, itemIds);

        timelinePhotoDeleteJobRepository.deleteAllByJobIdIn(jobs.stream()
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .toList());
        timelineItemRepository.deleteAllByIdIn(itemIds);
        return jobs.size();
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
    @Transactional
    public int deleteDraftSourceBatch(UUID subjectId, int batchSize) {
        return timelineDraftSourceItemRepository.deleteBySubjectId(subjectId.toString(), batchSize);
    }

    /**
     * 이 Item들의 현재 소유자가 대상 subject뿐인지 확인한다. junction 0이면 소유자가 없어 통과한다 —
     * 그 경우 소유 근거는 호출자가 이미 갖고 있다(record graph 또는 object_key namespace).
     */
    private void requireSoleOwner(UUID subjectId, List<Long> itemIds) {
        Set<String> owners = timelineEventItemRepository.findOwnerSubjectIdsByItemIdIn(itemIds).stream()
                .collect(Collectors.toSet());
        if (owners.stream().anyMatch(owner -> !subjectId.toString().equals(owner))) {
            throw new CrossSubjectItemException();
        }
    }
}
