package com.laimory.server.timeline.entity;

import com.laimory.server.common.BaseEntity;
import com.laimory.server.timeline.TimelinePhotoDeleteJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

/**
 * 마지막 TimelineItem 참조가 사라진 PHOTO의 S3 삭제 작업.
 *
 * <p>원 TimelineItem은 job이 있는 동안 보존된다. PENDING은 PATCH가 취소·재연결할 수 있는 대기 상태,
 * PROCESSING은 worker가 S3 삭제를 수행 중인 상태다. 처리 기회는 KST 생성일 D 기준 D+1~D+3 일일
 * 실행뿐이며(동시 request transaction이 먼저 수렴하도록 생성 당일은 제외), claim은 {@code updated_at}을
 * 갱신해 같은 날 재선택을 막는다. 창을 벗어난 미완료 job은 재시도 없이 보존된다. worker는 현재
 * association을 재확인한 뒤 S3 삭제에 성공한 job과 Item을 한 DB transaction에서 최종 hard delete한다.
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
                name = "idx_timeline_photo_delete_jobs_claim",
                columnList = "created_at, timeline_photo_delete_job_id")
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

    /** PENDING은 취소 가능, PROCESSING은 S3 삭제 진행 중이다. 완료·취소 상태는 행 삭제로 표현한다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, insertable = false)
    private TimelinePhotoDeleteJobStatus status;

    protected TimelinePhotoDeleteJob() {
    }
}
