package com.laimory.server.timeline.service;

import com.laimory.server.timeline.TimelinePhotoDeleteJobStatus;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PHOTO delete job의 enqueue, claim과 완료 transaction을 소유한다. */
@Service
@RequiredArgsConstructor
public class TimelinePhotoDeleteJobService {

    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_OBJECT_KEY_LENGTH = 255;
    private static final ZoneId WORKER_ZONE = ZoneId.of("Asia/Seoul");

    private final TimelinePhotoDeleteJobRepository timelinePhotoDeleteJobRepository;
    private final TimelineItemService timelineItemService;
    private final Clock clock;

    /**
     * 같은 Item 또는 object의 기존 작업을 보존하면서 없을 때만 enqueue한다. 신규 job은 생성 당일 claim
     * 대상에서 제외된다(claim의 {@code created_at < todayStart} 조건 — 처리 창 D+1~D+3의 시작 경계).
     * 처리 창과 저장 시각의 기준이 일치하도록 KST 시각 하나를 캡처해 두 감사 컬럼에 같이 쓴다.
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
     * KST 생성일 D 기준 D+1~D+3 처리 창 안에서 오늘 아직 처리하지 않은 자기 담당 작업을 일반 조회하고
     * {@code updated_at}을 claim 시각으로 갱신해 같은 날 재선택을 막는다. 반환 시 transaction과 row
     * lock은 끝났으므로 호출자는 외부 I/O를 안전하게 수행할 수 있다.
     */
    @Transactional
    public List<TimelinePhotoDeleteJob> claimEligible(int workerIndex, int totalWorkerCount, int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), WORKER_ZONE);
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime windowStart = todayStart.minusDays(3);
        LocalDateTime claimedAt = now.toLocalDateTime();
        List<TimelinePhotoDeleteJob> jobs = timelinePhotoDeleteJobRepository
                .findClaimable(windowStart, todayStart, workerIndex, totalWorkerCount, limit);
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

    /** 고아 처리 transaction에서 job 존재를 일반 조회한다. 잠금 읽기로 빈 인덱스 갭을 잠그지 않는다. */
    public Set<Long> findItemIdsWithJob(Collection<Long> timelineItemIds) {
        if (timelineItemIds == null || timelineItemIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(timelinePhotoDeleteJobRepository.findItemIdsWithJob(timelineItemIds));
    }

    /** 처리 창을 벗어나 재시도에서 제외된 미완료 작업 수. 경계는 claim과 같은 KST 규칙으로 계산한다. */
    public long countExpired() {
        LocalDateTime windowStart = ZonedDateTime.ofInstant(clock.instant(), WORKER_ZONE)
                .toLocalDate().atStartOfDay().minusDays(3);
        return timelinePhotoDeleteJobRepository.countCreatedBefore(windowStart);
    }

    /** S3 실패 job을 다음 일일 실행이 다시 claim하는 PENDING으로 되돌린다. */
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

    /** 완료된 작업을 ID로 제거한다. 빈 입력은 오류 없이 0을 돌려준다. */
    public int deleteByIds(Collection<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return 0;
        }
        return timelinePhotoDeleteJobRepository.deleteAllByJobIdIn(jobIds);
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
