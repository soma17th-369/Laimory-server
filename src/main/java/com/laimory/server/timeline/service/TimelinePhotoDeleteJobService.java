package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** timeline_photo_delete_jobs leaf 서비스. enqueue, oldest 조회와 성공 행 삭제만 소유한다. */
@Service
@RequiredArgsConstructor
public class TimelinePhotoDeleteJobService {

    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_OBJECT_KEY_LENGTH = 255;
    private static final ZoneId WORKER_ZONE = ZoneId.of("Asia/Seoul");

    private final TimelinePhotoDeleteJobRepository timelinePhotoDeleteJobRepository;
    private final Clock clock;

    /**
     * 같은 Item 또는 object의 기존 작업을 보존하면서 없을 때만 enqueue한다.
     *
     * @return 새 행을 만들었으면 {@code true}, UNIQUE 충돌로 기존 작업을 유지했으면 {@code false}
     */
    public boolean insertIfAbsent(long timelineItemId, String objectKey) {
        requireValidTimelineItemId(timelineItemId);
        requireValidObjectKey(objectKey);
        return timelinePhotoDeleteJobRepository.insertIfAbsent(timelineItemId, objectKey) == 1;
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
        int deferred = timelinePhotoDeleteJobRepository.deferUntil(jobIds, nextAvailableAt);
        if (deferred != jobIds.size()) {
            throw new IllegalStateException("PHOTO delete job claim count mismatch");
        }
        return List.copyOf(jobs);
    }

    public long countPending() {
        return timelinePhotoDeleteJobRepository.count();
    }

    public Optional<LocalDateTime> findOldestCreatedAt() {
        return timelinePhotoDeleteJobRepository.findOldestCreatedAt();
    }

    /** completion transaction에서 아직 남아 있는 성공 작업을 row lock으로 직렬화한다. */
    public List<TimelinePhotoDeleteJob> findExistingForCompletion(Collection<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return List.of();
        }
        return timelinePhotoDeleteJobRepository.findAllExistingForUpdate(jobIds);
    }

    /** S3 삭제에 성공한 작업만 ID로 제거한다. 빈 입력은 no-op이다. */
    public int deleteSucceeded(Collection<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return 0;
        }
        return timelinePhotoDeleteJobRepository.deleteAllByJobIdIn(jobIds);
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
