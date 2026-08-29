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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
     * 경합한 수동 PHOTO 추가가 먼저 commit하도록 생성 당일 claim 대상에서 제외된다(claim의
     * {@code created_at < todayStart} 조건). 처리 창과 저장 시각의 기준이 일치하도록 KST 시각 하나를
     * 캡처해 두 감사 컬럼에 같이 쓴다.
     *
     * @return 새 행을 만들었으면 {@code true}, UNIQUE 충돌로 기존 작업을 유지했으면 {@code false}
     */
    public boolean insertIfAbsent(long timelineItemId, String objectKey) {
        requireValidTimelineItemId(timelineItemId);
        requireValidObjectKey(objectKey);
        LocalDateTime auditAt = ZonedDateTime.ofInstant(clock.instant(), WORKER_ZONE).toLocalDateTime();
        return timelinePhotoDeleteJobRepository
                .insertIfAbsent(timelineItemId, objectKey, auditAt) == 1;
    }

    /**
     * KST 생성일 D 기준 D+1~D+3 처리 창 안에서 오늘 아직 처리하지 않은 작업을 row lock으로 분리하고
     * {@code updated_at}을 claim 시각으로 갱신해 같은 날 재선택을 막는다. 반환 시 transaction과 row
     * lock은 끝났으므로 호출자는 외부 I/O를 안전하게 수행할 수 있다.
     */
    @Transactional
    public List<TimelinePhotoDeleteJob> claimEligible(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), WORKER_ZONE);
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime windowStart = todayStart.minusDays(3);
        LocalDateTime claimedAt = now.toLocalDateTime();
        List<TimelinePhotoDeleteJob> jobs = timelinePhotoDeleteJobRepository
                .findClaimableForUpdateSkipLocked(windowStart, todayStart, limit);
        if (jobs.isEmpty()) {
            return List.of();
        }

        List<Long> jobIds = jobs.stream()
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .toList();
        int claimed = timelinePhotoDeleteJobRepository.markProcessing(
                jobIds, TimelinePhotoDeleteJobStatus.PROCESSING, claimedAt);
        if (claimed != jobIds.size()) {
            throw new IllegalStateException("PHOTO delete job claim count mismatch");
        }
        return List.copyOf(jobs);
    }

    /**
     * 주어진 Item 중 job을 가진 Item ID를 current read로 조회한다(orphan 스위퍼 전용 — 자세한 근거는
     * repository javadoc). 호출자의 transaction 안에서 실행돼야 의미가 있다.
     */
    public Set<Long> findItemIdsWithJob(Collection<Long> timelineItemIds) {
        if (timelineItemIds == null || timelineItemIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(timelinePhotoDeleteJobRepository.findItemIdsWithJobForShare(timelineItemIds));
    }

    /** 처리 창을 벗어나 재시도에서 제외된 미완료 작업 수. 경계는 claim과 같은 KST 규칙으로 계산한다. */
    public long countExpired() {
        LocalDateTime windowStart = ZonedDateTime.ofInstant(clock.instant(), WORKER_ZONE)
                .toLocalDate().atStartOfDay().minusDays(3);
        return timelinePhotoDeleteJobRepository.countCreatedBefore(windowStart);
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
     * 수동 PHOTO 추가(Event PATCH·Event 생성 POST)가 같은 object의 삭제 대기 job을 취소하고 보존 Item을
     * 재사용한다.
     * 오늘 claim된 PROCESSING job은 S3 삭제 중이므로 같은 object를 새 Item으로 만들지 않게 409로
     * 거절한다. {@code updated_at}이 전날 이전인 PROCESSING은 crash가 남긴 stale 행이라 취소를 허용한다.
     */
    @Transactional
    public Optional<Long> cancelPendingForRelink(String objectKey, String rawId) {
        requireValidObjectKey(objectKey);
        TimelinePhotoDeleteJob job = timelinePhotoDeleteJobRepository.findByObjectKeyForUpdate(objectKey)
                .orElse(null);
        if (job == null) {
            return Optional.empty();
        }

        LocalDateTime todayStart = ZonedDateTime.ofInstant(clock.instant(), WORKER_ZONE)
                .toLocalDate().atStartOfDay();
        if (job.getStatus() == TimelinePhotoDeleteJobStatus.PROCESSING
                && !job.getUpdatedAt().isBefore(todayStart)) {
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
     * claimed job이 S3 삭제 직전에도 orphan인지 재검증한다. 취소 사유는 두 가지이고 둘 다 같은
     * transaction에서 job을 지워 S3 대상에서 뺀다.
     *
     * <ol>
     *   <li><b>자기 Item 재연결</b> — job이 가리키는 Item이 다시 Event에 연결됐다.</li>
     *   <li><b>같은 object key의 다른 Item 생존</b> — 같은 S3 객체를 가리키는 <i>다른</i> PHOTO Item이
     *       junction을 갖고 있다. 이 경우 job 대상 Item만 보면 여전히 orphan이라 (1)로는 걸러지지 않는데,
     *       그대로 지우면 살아 있는 Item의 사진이 사라진다. orphan 스위퍼가 enqueue 시점에 같은 가드를
     *       두지만 그 조회와 commit 사이의 창은 닫히지 않으므로, job 생성 다음 날 실행되는 이 지점이
     *       최종 권위다.</li>
     * </ol>
     *
     * <p>(2)의 판정은 filename을 coarse filter로 쓰되 최종 비교는 full object key로 한다 — filename만
     * 같고 namespace가 다른 남의 Item이 취소를 유발하지 않게 한다. 정상 경로에서는 발화하지 않는다
     * (filename은 presign이 발급한 UUIDv7이라 서로 다른 업로드가 같은 key를 갖지 않는다).
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
        Set<String> liveObjectKeys = liveObjectKeysOf(claimedJobs);

        List<TimelinePhotoDeleteJob> cancelTargets = claimedJobs.stream()
                .filter(job -> linkedItemIds.contains(job.getTimelineItemId())
                        || liveObjectKeys.contains(job.getObjectKey()))
                .toList();
        if (cancelTargets.isEmpty()) {
            return new ValidationResult(List.copyOf(claimedJobs), 0);
        }

        Set<Long> cancelJobIds = cancelTargets.stream()
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int cancelled = deleteByIds(List.copyOf(cancelJobIds));
        List<TimelinePhotoDeleteJob> orphanJobs = claimedJobs.stream()
                .filter(job -> !cancelJobIds.contains(job.getTimelinePhotoDeleteJobId()))
                .toList();
        return new ValidationResult(List.copyOf(orphanJobs), cancelled);
    }

    /**
     * claim한 job의 object key 중 <b>junction이 살아 있는 다른 PHOTO Item</b>이 참조하는 key 집합.
     * 살아 있는 Item의 key는 저장된 URL이 아니라 소유 subject에서 계산하므로, 그 Item의 {@code photoUrl}이
     * 손상돼 있어도 보호 대상에서 빠지지 않는다.
     */
    private Set<String> liveObjectKeysOf(List<TimelinePhotoDeleteJob> claimedJobs) {
        Set<String> filenames = claimedJobs.stream()
                .map(job -> filenameOf(job.getObjectKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return timelineItemService.findLiveObjectKeysByFilenames(filenames);
    }

    private static String filenameOf(String objectKey) {
        if (objectKey == null) {
            return null;
        }
        int separator = objectKey.lastIndexOf('/');
        return separator < 0 || separator == objectKey.length() - 1
                ? null
                : objectKey.substring(separator + 1);
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
