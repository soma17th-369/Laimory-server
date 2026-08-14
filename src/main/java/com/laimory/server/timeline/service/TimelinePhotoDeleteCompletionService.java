package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S3 삭제가 확인된 PHOTO job의 DB 완료 경계.
 *
 * <p>job FK가 원문 Item의 선삭제를 막으므로 job을 먼저 지우고 Item을 지운다. Item 삭제가 실패하면 같은
 * transaction에서 job 삭제도 rollback되어 다음 worker 실행이 S3의 미존재 성공 의미로 다시 수렴한다.
 * 늦은 중복 completion의 job 삭제 0건은 Item을 다시 지우지 않고 수렴한다. 일부 job만 지워졌다면
 * 재연결 취소 또는 다른 completion과의 경합을 구분할 수 없으므로 transaction을 rollback한다.
 */
@Service
@RequiredArgsConstructor
public class TimelinePhotoDeleteCompletionService {

    private final TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;
    private final TimelineItemService timelineItemService;

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
        int deletedJobs = timelinePhotoDeleteJobService.deleteByIds(jobIds);
        if (deletedJobs == 0) {
            return 0;
        }
        if (deletedJobs != jobIds.size()) {
            throw new IllegalStateException("PHOTO delete job completion count mismatch");
        }
        timelineItemService.deleteByIds(itemIds);
        return deletedJobs;
    }
}
