package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고아의 최초 관측과 처리, S3 삭제가 확인된 PHOTO Item 삭제를 각각 독립 transaction에서 수행한다.
 * S3 호출은 이 서비스가 하지 않는다 — {@link TimelineOrphanItemSweeper}가 transaction 밖에서 요청한다(#551).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineOrphanItemSweepService {

    private static final ZoneId OBSERVATION_ZONE = ZoneId.of("Asia/Seoul");

    private final TimelineItemService timelineItemService;
    private final TimelineEventItemService timelineEventItemService;
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

    public long countStaleObservedOrphans(int workerIndex, int totalWorkerCount) {
        LocalDateTime staleBefore = LocalDateTime.ofInstant(clock.instant(), OBSERVATION_ZONE).minusHours(72);
        return timelineItemService.countStaleObservedOrphans(workerIndex, totalWorkerCount, staleBefore);
    }

    /**
     * 관측이 commit된 뒤 선택된 PK를 새 transaction에서 읽고 재검증·분류한다. non-PHOTO와 key를 복원할 수 없는
     * PHOTO는 여기서 지운다. 유효 PHOTO는 지우지 않고 반환한다 — S3 삭제가 확인된 뒤에만
     * {@link #deletePhotoItems}로 지워, 결과를 받지 못하면(실패·응답 누락·프로세스 종료) 다음 run이 다시 집는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SweepBatchResult sweepBatch(List<Long> candidateIds) {
        List<TimelineItem> items = timelineItemService.findByIds(candidateIds);
        List<TimelineItem> actionable = revalidate(items);
        int revalidationDropped = candidateIds.size() - actionable.size();

        int invalidDeleted = 0;
        int nonPhotoDeleted = 0;
        List<Long> immediateDeleteIds = new ArrayList<>();
        List<PhotoDeletion> photoDeletions = new ArrayList<>();
        for (TimelineItem item : actionable) {
            if (item.getItemType() != ItemType.PHOTO) {
                nonPhotoDeleted++;
                immediateDeleteIds.add(item.getTimelineItemId());
                continue;
            }
            Optional<String> objectKey = restoreObjectKey(item);
            if (objectKey.isEmpty()) {
                invalidDeleted++;
                immediateDeleteIds.add(item.getTimelineItemId());
                continue;
            }
            photoDeletions.add(new PhotoDeletion(item.getTimelineItemId(), objectKey.get()));
        }
        timelineItemService.deleteByIds(immediateDeleteIds);

        return new SweepBatchResult(candidateIds.size(), revalidationDropped, invalidDeleted, nonPhotoDeleted,
                List.copyOf(photoDeletions));
    }

    /**
     * S3 삭제가 확인된 PHOTO Item을 지운다. 그 사이 다른 경로(계정 삭제)가 먼저 지운 행은 0행으로 끝난다.
     * 고아에 연결을 다시 만드는 writer가 없어 재검증하지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deletePhotoItems(Collection<Long> timelineItemIds) {
        timelineItemService.deleteByIds(timelineItemIds);
    }

    /**
     * 새 처리 snapshot에서 활성 Event 연결을 일반 조회한다. 삭제된 Event의 연결만 남은 Item은 고아다(#569).
     * DML 경합 실패는 전체 처리가 rollback된다.
     */
    private List<TimelineItem> revalidate(List<TimelineItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<Long> itemIds = items.stream().map(TimelineItem::getTimelineItemId).toList();
        Set<Long> linked = timelineEventItemService.findActivelyLinkedItemIds(itemIds);
        return items.stream()
                .filter(item -> !linked.contains(item.getTimelineItemId()))
                .toList();
    }

    /**
     * 저장본에서 full object key를 복원한다. junction을 잃어 subject를 알 수 없으므로 {@code photoUrl}
     * path가 유일한 경로다. payload 파싱 실패·filename 부재·URL 손상·URL과 payload filename 불일치는
     * 모두 "복원 불가"로 같은 처리(S3 삭제 생략 + 행 삭제, S3 orphan 허용)다.
     */
    private Optional<String> restoreObjectKey(TimelineItem item) {
        PhotoPayload photo;
        try {
            photo = objectMapper.treeToValue(item.getPayload(), PhotoPayload.class);
        } catch (JsonProcessingException | RuntimeException exception) {
            log.warn("orphan PHOTO payload 파싱 실패, S3 삭제 생략: timelineItemId={} exceptionType={}",
                    item.getTimelineItemId(), exception.getClass().getSimpleName());
            return Optional.empty();
        }
        if (photo == null || photo.filename() == null || photo.filename().isBlank()) {
            log.warn("orphan PHOTO filename 없음, S3 삭제 생략: timelineItemId={}", item.getTimelineItemId());
            return Optional.empty();
        }
        Optional<String> objectKey = PhotoObjectKeys.objectKeyFromServingUrl(photo.photoUrl());
        if (objectKey.isEmpty() || !objectKey.get().endsWith("/" + photo.filename())) {
            log.warn("orphan PHOTO object key 복원 실패, S3 삭제 생략: timelineItemId={}",
                    item.getTimelineItemId());
            return Optional.empty();
        }
        return objectKey;
    }

    /** S3 삭제를 요청할 PHOTO Item과 그 full object key. */
    public record PhotoDeletion(long timelineItemId, String objectKey) {
    }

    /** 선택 후보 수와 재검증 탈락·즉시 삭제 결과, S3 삭제를 요청할 PHOTO. 후보 수는 실제 삭제 성공 수가 아니다. */
    public record SweepBatchResult(
            int selected,
            int revalidationDropped,
            int invalidDeleted,
            int nonPhotoDeleted,
            List<PhotoDeletion> photoDeletions) {
    }
}
