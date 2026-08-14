package com.laimory.server.timeline.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 마지막 TimelineItem 참조가 사라진 PHOTO의 S3 삭제 작업.
 *
 * <p>행 존재 자체가 처리 대기 상태다. 원 TimelineItem은 job이 있는 동안 보존되며, 신규 job은 동시
 * request transaction이 수렴하도록 다음 날부터 claim한다. worker는 현재 association을 재확인한 뒤 S3
 * 삭제에 성공한 job과 Item을 한 DB transaction에서 최종 hard delete한다.
 */
@Entity
@Table(
        name = "timeline_photo_delete_jobs",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_timeline_photo_delete_jobs_item",
                        columnNames = "timeline_item_id"),
                @UniqueConstraint(
                        name = "uq_timeline_photo_delete_jobs_object",
                        columnNames = "object_key")
        },
        indexes = @Index(
                name = "idx_timeline_photo_delete_jobs_available",
                columnList = "available_at, created_at, timeline_photo_delete_job_id")
)
@Getter
public class TimelinePhotoDeleteJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timeline_photo_delete_job_id")
    private Long timelinePhotoDeleteJobId;

    /** worker가 S3 성공 뒤 최종 hard delete할 원문 PHOTO TimelineItem ID. */
    @Column(name = "timeline_item_id", nullable = false)
    private Long timelineItemId;

    /** worker가 삭제할 full S3 object key. 로그·metric·exception message에 노출하지 않는다. */
    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    /** 이 시각부터 다음 worker claim 대상이 된다. DB default는 구 binary enqueue 호환에도 필요하다. */
    @Column(name = "available_at", nullable = false, insertable = false)
    private LocalDateTime availableAt;

    protected TimelinePhotoDeleteJob() {
    }
}
