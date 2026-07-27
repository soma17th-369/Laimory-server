package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** timeline_photo_delete_jobs leaf 서비스. enqueue, oldest 조회와 성공 행 삭제만 소유한다. */
@Service
@RequiredArgsConstructor
public class TimelinePhotoDeleteJobService {

    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_OBJECT_KEY_LENGTH = 255;

    private final TimelinePhotoDeleteJobRepository timelinePhotoDeleteJobRepository;

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

    /** 오래된 순(created_at, PK)으로 최대 {@code limit}개를 조회한다. */
    public List<TimelinePhotoDeleteJob> findOldest(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        return timelinePhotoDeleteJobRepository.findOldest(PageRequest.of(0, limit));
    }

    public long countPending() {
        return timelinePhotoDeleteJobRepository.count();
    }

    public Optional<LocalDateTime> findOldestCreatedAt() {
        return timelinePhotoDeleteJobRepository.findOldestCreatedAt();
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
