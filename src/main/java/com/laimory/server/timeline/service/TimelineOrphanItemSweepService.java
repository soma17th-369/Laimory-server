package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 고아의 최초 관측과 처리를 각각 독립 transaction에서 수행한다. S3는 호출하지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineOrphanItemSweepService {

    private static final ZoneId OBSERVATION_ZONE = ZoneId.of("Asia/Seoul");

    private final TimelineItemService timelineItemService;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 후보 조회와 최초 기록을 commit한 뒤 PK만 반환해 처리 snapshot과 분리한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> observeBatch(int workerIndex, int totalWorkerCount, int limit) {
        List<Long> ids = timelineItemService.findOrphanCandidates(workerIndex, totalWorkerCount, limit).stream()
                .map(TimelineItem::getTimelineItemId).toList();
        timelineItemService.markOrphanObserved(ids, LocalDateTime.ofInstant(clock.instant(), OBSERVATION_ZONE));
        return ids;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public long countStaleObservedOrphans(int workerIndex, int totalWorkerCount) {
        LocalDateTime staleBefore = LocalDateTime.ofInstant(clock.instant(), OBSERVATION_ZONE).minusHours(72);
        return timelineItemService.countStaleObservedOrphans(workerIndex, totalWorkerCount, staleBefore);
    }

    /** 관측이 commit된 뒤 선택된 PK를 새 transaction에서 읽고 재검증·분류·처리한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SweepBatchResult sweepBatch(List<Long> candidateIds) {
        List<TimelineItem> items = timelineItemService.findByIds(candidateIds);
        List<TimelineItem> actionable = revalidate(items);
        int revalidationDropped = candidateIds.size() - actionable.size();

        Counters counters = new Counters();
        List<Long> immediateDeleteIds = new ArrayList<>();
        List<KeyedPhoto> keyedPhotos = new ArrayList<>();
        for (TimelineItem item : actionable) {
            if (item.getItemType() != ItemType.PHOTO) {
                counters.nonPhotoDeleted++;
                immediateDeleteIds.add(item.getTimelineItemId());
                continue;
            }
            Optional<String> objectKey = restoreObjectKey(item);
            if (objectKey.isEmpty()) {
                counters.invalidDeleted++;
                immediateDeleteIds.add(item.getTimelineItemId());
                continue;
            }
            keyedPhotos.add(new KeyedPhoto(item, objectKey.get()));
        }

        schedulePhotoDeletions(keyedPhotos, counters, immediateDeleteIds);
        timelineItemService.deleteByIds(immediateDeleteIds);

        return new SweepBatchResult(candidateIds.size(), revalidationDropped,
                counters.photoScheduled, counters.photoAlreadyJob, counters.keyShared,
                counters.invalidDeleted, counters.nonPhotoDeleted);
    }

    /** 새 처리 snapshot에서 연결과 job을 일반 조회한다. DML 경합 실패는 전체 처리가 rollback된다. */
    private List<TimelineItem> revalidate(List<TimelineItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<Long> itemIds = items.stream().map(TimelineItem::getTimelineItemId).toList();
        Set<Long> withJob = timelinePhotoDeleteJobService.findItemIdsWithJob(itemIds);
        Set<Long> linked = timelineEventItemService.findByTimelineItemIds(itemIds).stream()
                .map(TimelineEventItem::getTimelineItemId)
                .collect(Collectors.toSet());
        return items.stream()
                .filter(item -> !withJob.contains(item.getTimelineItemId()))
                .filter(item -> !linked.contains(item.getTimelineItemId()))
                .toList();
    }

    /**
     * object key 그룹 규칙으로 job 소유자를 정하고 나머지 행은 삭제 대상에 넣는다.
     *
     * <ul>
     *   <li>같은 key를 <b>junction이 살아 있는</b> Item이 참조하면 job을 만들지 않는다 — S3 객체는 그
     *       Item의 생애주기가 계속 소유한다. 살아 있는 쪽의 key는 저장된 URL이 아니라 소유 subject에서
     *       계산하므로 그 Item의 {@code photoUrl}이 손상돼 있어도 놓치지 않는다.</li>
     *   <li>전부 orphan이면 같은 key를 참조하는 orphan 중 <b>최소 id</b>가 job 소유자다. 삭제 순서에
     *       의존하지 않아 같은 batch 안이든 밖이든 같은 결과로 수렴한다.</li>
     * </ul>
     */
    private void schedulePhotoDeletions(List<KeyedPhoto> keyedPhotos, Counters counters,
                                        List<Long> immediateDeleteIds) {
        if (keyedPhotos.isEmpty()) {
            return;
        }
        Set<String> filenames = keyedPhotos.stream()
                .map(KeyedPhoto::filename)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> liveObjectKeys = timelineItemService.findLiveObjectKeysByFilenames(filenames);
        Map<String, Long> ownerIdByObjectKey = ownerIdByObjectKey(filenames);

        for (KeyedPhoto photo : keyedPhotos) {
            long itemId = photo.item().getTimelineItemId();
            if (liveObjectKeys.contains(photo.objectKey())) {
                counters.keyShared++;
                immediateDeleteIds.add(itemId);
                continue;
            }
            // equals로 비교한다 — Long vs long은 unboxing이라 지금도 값 비교지만, 한쪽 타입이 바뀌면
            // 조용히 참조 비교가 되어 소유자 판정이 뒤집힌다(같은 key의 orphan이 전부 job 없이 삭제).
            Long ownerId = ownerIdByObjectKey.get(photo.objectKey());
            if (ownerId != null && !ownerId.equals(itemId)) {
                counters.keyShared++;
                immediateDeleteIds.add(itemId);
                continue;
            }
            if (timelinePhotoDeleteJobService.insertIfAbsent(itemId, photo.objectKey())) {
                counters.photoScheduled++;
                continue;
            }
            // insert ignore는 item UNIQUE와 object UNIQUE 어느 쪽으로 막혀도 실패를 구분하지 않는다.
            // 자기 job이 보이면 행을 보존하고, 아니면 다른 Item이 같은 object key를 소유한 것으로 처리한다.
            // 처리 snapshot 뒤 동시 생성된 자기 job은 안 보일 수 있다. 그 경우 FK가 삭제를 거절하고
            // 처리 transaction 전체를 rollback하며, 이미 commit한 관측 기록은 남는다.
            if (timelinePhotoDeleteJobService.findItemIdsWithJob(List.of(itemId)).contains(itemId)) {
                counters.photoAlreadyJob++;
            } else {
                counters.keyShared++;
                immediateDeleteIds.add(itemId);
            }
        }
    }

    /** 같은 object key를 참조하는 orphan 중 최소 id. 복원 불가한 행은 소유자 후보에서 빠진다. */
    private Map<String, Long> ownerIdByObjectKey(Set<String> filenames) {
        Map<String, Long> ownerIdByObjectKey = new HashMap<>();
        for (TimelineItemRepository.OrphanPhotoKeyRow row
                : timelineItemService.findUnlinkedPhotoKeysByFilenames(filenames)) {
            PhotoObjectKeys.objectKeyFromServingUrl(row.getPhotoUrl()).ifPresent(objectKey ->
                    ownerIdByObjectKey.merge(objectKey, row.getTimelineItemId(), Math::min));
        }
        return ownerIdByObjectKey;
    }

    /**
     * 저장본에서 full object key를 복원한다. junction을 잃어 subject를 알 수 없으므로 {@code photoUrl}
     * path가 유일한 경로다. payload 파싱 실패·filename 부재·URL 손상·URL과 payload filename 불일치는
     * 모두 "복원 불가"로 같은 처리(job 생략 + 행 삭제, S3 orphan 허용 — 기존 삭제 흐름과 같은 규칙)다.
     */
    private Optional<String> restoreObjectKey(TimelineItem item) {
        PhotoPayload photo;
        try {
            photo = objectMapper.treeToValue(item.getPayload(), PhotoPayload.class);
        } catch (JsonProcessingException | RuntimeException exception) {
            log.warn("orphan PHOTO payload 파싱 실패, 삭제 job 생략: timelineItemId={} exceptionType={}",
                    item.getTimelineItemId(), exception.getClass().getSimpleName());
            return Optional.empty();
        }
        if (photo == null || photo.filename() == null || photo.filename().isBlank()) {
            log.warn("orphan PHOTO filename 없음, 삭제 job 생략: timelineItemId={}", item.getTimelineItemId());
            return Optional.empty();
        }
        Optional<String> objectKey = PhotoObjectKeys.objectKeyFromServingUrl(photo.photoUrl());
        if (objectKey.isEmpty() || !objectKey.get().endsWith("/" + photo.filename())) {
            log.warn("orphan PHOTO object key 복원 실패, 삭제 job 생략: timelineItemId={}",
                    item.getTimelineItemId());
            return Optional.empty();
        }
        return objectKey;
    }

    private record KeyedPhoto(TimelineItem item, String objectKey) {

        String filename() {
            return objectKey.substring(objectKey.lastIndexOf('/') + 1);
        }
    }

    private static final class Counters {

        private int photoScheduled;
        private int photoAlreadyJob;
        private int keyShared;
        private int invalidDeleted;
        private int nonPhotoDeleted;
    }

    /** 선택 후보 수와 재검증 탈락·처리 결과. 후보 수는 실제 삭제 성공 수가 아니다. */
    public record SweepBatchResult(
            int selected,
            int revalidationDropped,
            int photoScheduled,
            int photoAlreadyJob,
            int keyShared,
            int invalidDeleted,
            int nonPhotoDeleted) {
    }
}
