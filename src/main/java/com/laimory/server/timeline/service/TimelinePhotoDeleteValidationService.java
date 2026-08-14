package com.laimory.server.timeline.service;

import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelinePhotoDeleteJob;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** claimed PHOTO job이 여전히 orphan인지 S3 삭제 직전에 재검증하는 transaction 경계. */
@Service
public class TimelinePhotoDeleteValidationService {

    private final TimelineEventItemService timelineEventItemService;
    private final TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;

    public TimelinePhotoDeleteValidationService(
            TimelineEventItemService timelineEventItemService,
            TimelinePhotoDeleteJobService timelinePhotoDeleteJobService) {
        this.timelineEventItemService = timelineEventItemService;
        this.timelinePhotoDeleteJobService = timelinePhotoDeleteJobService;
    }

    /**
     * job 생성 뒤 다른 Event에 다시 연결된 Item은 삭제 의무가 아니므로 job을 취소하고 S3 대상에서 제외한다.
     * 신규 job은 다음 날부터 eligible하므로 job 생성과 경합한 request transaction이 먼저 수렴한 뒤 이 검증을 거친다.
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

        List<TimelinePhotoDeleteJob> relinkedJobs = claimedJobs.stream()
                .filter(job -> linkedItemIds.contains(job.getTimelineItemId()))
                .toList();
        List<Long> relinkedJobIds = relinkedJobs.stream()
                .map(TimelinePhotoDeleteJob::getTimelinePhotoDeleteJobId)
                .distinct()
                .toList();
        int cancelled = timelinePhotoDeleteJobService.deleteByIds(relinkedJobIds);
        List<TimelinePhotoDeleteJob> orphanJobs = claimedJobs.stream()
                .filter(job -> !linkedItemIds.contains(job.getTimelineItemId()))
                .toList();
        return new ValidationResult(List.copyOf(orphanJobs), cancelled);
    }

    public record ValidationResult(List<TimelinePhotoDeleteJob> orphanJobs, int cancelledJobs) {
    }
}
