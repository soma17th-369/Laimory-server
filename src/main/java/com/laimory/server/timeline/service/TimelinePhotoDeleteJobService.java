package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelinePhotoDeleteJobStatus;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PHOTO delete job의 enqueue, claim, orphan 재검증과 완료 transaction을 소유한다. */
@Service
@RequiredArgsConstructor
public class TimelinePhotoDeleteJobService {

    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_OBJECT_KEY_LENGTH = 255;
    private static final ZoneId WORKER_ZONE = ZoneId.of("Asia/Seoul");

    private final TimelinePhotoDeleteJobRepository timelinePhotoDeleteJobRepository;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;
    private final Clock clock;

    /**
     * 같은 Item 또는 object의 기존 작업을 보존하면서 없을 때만 enqueue한다. 신규 job은 삭제 transaction과
     * 경합한 Event PATCH가 먼저 commit하도록 다음 Seoul calendar day 00:00부터 claim 가능하게 한다.
     *
     * @return 새 행을 만들었으면 {@code true}, UNIQUE 충돌로 기존 작업을 유지했으면 {@code false}
     */
    public boolean insertIfAbsent(long timelineItemId, String objectKey) {
        requireValidTimelineItemId(timelineItemId);
        requireValidObjectKey(objectKey);
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), WORKER_ZONE);
        LocalDateTime initialAvailableAt = now.toLocalDate().plusDays(1).atStartOfDay();
        return timelinePhotoDeleteJobRepository
                .insertIfAbsent(timelineItemId, objectKey, initialAvailableAt) == 1;
    }

    /**
     * 현재 eligible한 작업을 row lock으로 분리하고 다음 일일 실행 전 eligibility 시각으로 미룬다.
     * 반환 시 transaction과 row lock은 끝났으므로 호출자는 외부 I/O를 안전하게 수행할 수 있다.
     */
    @Transactional
    public List<TimelinePhotoDeleteJob> claimEligible(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), WORKER_ZONE);
        LocalDateTime eligibleAt = now.toLocalDateTime();
        LocalDateTime nextAvailableAt = now.toLocalDate().plusDays(1).atStartOfDay();
        List<TimelinePhotoDeleteJob> jobs = timelinePhotoDeleteJobRepository
                .findEligibleForUpdateSkipLocked(eligibleAt, limit);
        if (jobs.isEmpty()) {
            return List.of();
        }

        List<Long> jobIds = jobs.stream()
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .toList();
        int deferred = timelinePhotoDeleteJobRepository.markProcessingUntil(
                jobIds, TimelinePhotoDeleteJobStatus.PROCESSING, nextAvailableAt);
        if (deferred != jobIds.size()) {
            throw new IllegalStateException("PHOTO delete job claim count mismatch");
        }
        return List.copyOf(jobs);
    }

    /** S3 실패·검증 실패 job을 PATCH가 다시 취소할 수 있는 PENDING으로 되돌린다. */
    @Transactional
    public int markPendingForRetry(Collection<TimelinePhotoDeleteJob> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return 0;
        }
        List<Long> jobIds = jobs.stream()
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .distinct()
                .toList();
        return timelinePhotoDeleteJobRepository.markPending(
                jobIds, TimelinePhotoDeleteJobStatus.PENDING, TimelinePhotoDeleteJobStatus.PROCESSING);
    }

    /**
     * Event PATCH가 같은 object의 삭제 대기 job을 취소하고 보존 Item을 재사용한다.
     * 유효한 PROCESSING job은 S3 삭제 중이므로 같은 object를 새 Item으로 만들지 않게 409로 거절한다.
     */
    @Transactional
    public Optional<Long> cancelPendingForRelink(String objectKey, String rawId) {
        requireValidObjectKey(objectKey);
        TimelinePhotoDeleteJob job = timelinePhotoDeleteJobRepository.findByObjectKeyForUpdate(objectKey)
                .orElse(null);
        if (job == null) {
            return Optional.empty();
        }

        LocalDateTime now = ZonedDateTime.ofInstant(clock.instant(), WORKER_ZONE).toLocalDateTime();
        if (job.getStatus() == TimelinePhotoDeleteJobStatus.PROCESSING
                && job.getAvailableAt().isAfter(now)) {
            throw new BusinessException(ExceptionType.PHOTO_DELETE_IN_PROGRESS);
        }

        TimelineItem item = timelineItemService.findById(job.getTimelineItemId())
                .orElseThrow(() -> new IllegalStateException("PHOTO delete job item not found"));
        if (item.getItemType() != ItemType.PHOTO || !item.getRawId().equals(rawId)) {
            throw new IllegalArgumentException("filename is already used by another timeline item");
        }

        int deleted = deleteByIds(List.of(job.getTimelinePhotoDeleteJobId()));
        if (deleted != 1) {
            throw new IllegalStateException("PHOTO delete job cancellation count mismatch");
        }
        return Optional.of(item.getTimelineItemId());
    }

    /** 완료되거나 재연결되어 취소된 작업을 ID로 제거한다. 빈 입력은 no-op이다. */
    public int deleteByIds(Collection<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return 0;
        }
        return timelinePhotoDeleteJobRepository.deleteAllByJobIdIn(jobIds);
    }

    /**
     * claimed job이 S3 삭제 직전에도 orphan인지 재검증한다. 다른 Event에 다시 연결된 Item의 job은 같은
     * transaction에서 취소하고 S3 대상에서 제외한다.
     */
    @Transactional
    public ValidationResult retainOrphanJobs(List<TimelinePhotoDeleteJob> claimedJobs) {
        if (claimedJobs.isEmpty()) {
            return new ValidationResult(List.of(), 0);
        }

        List<Long> itemIds = claimedJobs.stream()
                .map(TimelinePhotoDeleteJob::getTimelineItemId)
                .distinct()
                .toList();
        Set<Long> linkedItemIds = timelineEventItemService.findByTimelineItemIds(itemIds).stream()
                .map(TimelineEventItem::getTimelineItemId)
                .collect(Collectors.toSet());
        if (linkedItemIds.isEmpty()) {
            return new ValidationResult(List.copyOf(claimedJobs), 0);
        }

        List<Long> relinkedJobIds = claimedJobs.stream()
                .filter(job -> linkedItemIds.contains(job.getTimelineItemId()))
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .distinct()
                .toList();
        int cancelled = deleteByIds(relinkedJobIds);
        List<TimelinePhotoDeleteJob> orphanJobs = claimedJobs.stream()
                .filter(job -> !linkedItemIds.contains(job.getTimelineItemId()))
                .toList();
        return new ValidationResult(List.copyOf(orphanJobs), cancelled);
    }

    /**
     * S3 삭제가 확인된 job과 원문 PHOTO Item을 같은 transaction에서 완료한다. job FK가 Item의 선삭제를
     * 막으므로 job을 먼저 지우고 Item을 지운다. 늦은 중복 completion은 job 삭제 0건으로 수렴한다.
     */
    @Transactional
    public int completeSucceeded(List<TimelinePhotoDeleteJob> succeededJobs) {
        if (succeededJobs.isEmpty()) {
            return 0;
        }

        List<Long> jobIds = succeededJobs.stream()
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .distinct()
                .toList();
        List<Long> itemIds = succeededJobs.stream()
                .map(TimelinePhotoDeleteJob::getTimelineItemId)
                .distinct()
                .toList();
        int deletedJobs = deleteByIds(jobIds);
        if (deletedJobs == 0) {
            return 0;
        }
        if (deletedJobs != jobIds.size()) {
            throw new IllegalStateException("PHOTO delete job completion count mismatch");
        }
        timelineItemService.deleteByIds(itemIds);
        return deletedJobs;
    }

    public record ValidationResult(List<TimelinePhotoDeleteJob> orphanJobs, int cancelledJobs) {
    }

    private void requireValidTimelineItemId(long timelineItemId) {
        if (timelineItemId <= 0) {
            throw new IllegalArgumentException("timelineItemId must be positive");
        }
    }

    private void requireValidObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.length() > MAX_OBJECT_KEY_LENGTH) {
            throw new IllegalArgumentException("objectKey must be non-blank and at most 255 ASCII characters");
        }
        if (objectKey.chars().anyMatch(character -> character > 0x7f)) {
            throw new IllegalArgumentException("objectKey must be non-blank and at most 255 ASCII characters");
        }
    }
}
