package com.laimory.server.timeline.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.springframework.data.domain.Persistable;

/**
 * AI 결과 저장 영수증. task 하나가 final graph에 반영됐다는 사실을 <b>결과 저장과 같은 transaction</b>에
 * 기록해, 응답 유실 후 재시도가 같은 결과를 중복 저장하지 않게 한다.
 *
 * <p>이 행이 필요한 이유: 최종 graph({@code timeline_events}/{@code timeline_items})에는 {@code task_id}가
 * 없고 채택된 staging source는 같은 transaction에서 삭제되므로, 기존 테이블만으로는 "이 task가 이미
 * 반영됐는가"를 durable하게 판별할 수 없다.
 *
 * <p>PK가 {@code task_id}라는 점이 load-bearing이다 — 저장 transaction의 <b>첫 write</b>가 이 INSERT이며,
 * 재시도와 동시 중복 요청이 여기서 duplicate key로 직렬화된다(별도 record lock을 두지 않는 이유).
 * 결과 내용은 저장하지 않는다 — 같은 task에 다른 결과가 오는 상황은 AI 버그 시나리오뿐이라 receipt 존재를
 * "이미 반영"으로만 해석한다.
 *
 * <p>{@link Persistable}로 {@code isNew()}를 고정 true로 두는 이유: ID가 assigned(생성 전략 없음)라
 * {@code save()}가 기본적으로 merge(SELECT 후 INSERT/UPDATE)로 동작해, 이미 있는 task의 재시도가 duplicate
 * key 대신 조용한 UPDATE가 된다. 이 엔티티는 생성만 하고 갱신하지 않으므로 항상 persist로 강제해
 * 중복을 예외로 드러낸다.
 */
@Entity
@Table(name = "timeline_ai_result_receipts")
@Getter
public class TimelineAiResultReceipt extends BaseEntity implements Persistable<String> {

    /** draft task ID(UUIDv7). 대리 키를 두지 않고 task ID 자체가 PK다 — 멱등성 판정의 단일 축이다. */
    @Id
    @Column(name = "task_id", length = 36)
    private String taskId;

    @Column(name = "daily_record_id", nullable = false)
    private Long dailyRecordId;

    protected TimelineAiResultReceipt() {
    }

    private TimelineAiResultReceipt(String taskId, Long dailyRecordId) {
        this.taskId = taskId;
        this.dailyRecordId = dailyRecordId;
    }

    public static TimelineAiResultReceipt of(String taskId, Long dailyRecordId) {
        return new TimelineAiResultReceipt(taskId, dailyRecordId);
    }

    @Override
    public String getId() {
        return taskId;
    }

    /** 항상 신규 — 이 엔티티는 INSERT만 하며 갱신 경로가 없다(위 클래스 주석의 duplicate key 계약). */
    @Override
    public boolean isNew() {
        return true;
    }
}
