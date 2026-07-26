package com.laimory.server.timeline.entity;

import com.laimory.server.common.BaseEntity;
import com.laimory.server.timeline.TimelineEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 타임라인 이벤트. daily_record에 plain Long FK로 연결(@OneToMany 미사용 - 서비스=레포 1개 규칙 보존).
 * memo는 사용자가 나중에 작성하므로 생성 시점엔 비어 있다.
 *
 * <p>{@code @DynamicUpdate}: 통합 PATCH에서 memo가 생략된 요청과 memo PUT이 서로 다른 필드 그룹을
 * 갱신할 수 있다. Hibernate 기본 UPDATE는 모든 updatable 컬럼을 SET에 포함하므로, 두 요청이 같은 row를 읽고
 * 순차 커밋하면 나중 커밋이 상대의 변경을 자신의 로드 시점 스냅샷으로 되돌린다(교차-필드 lost update).
 * dynamic update는 실제 변경된 컬럼만 SET해 이 경로를 제거한다(같은 필드 동시 수정은 여전히
 * last-write-wins — 필드 그룹이 겹치지 않는 현 계약에서는 충분하다). 비용은 flush 시 SQL 동적 생성.
 */
@Entity
@Table(name = "timeline_events")
@DynamicUpdate
@Getter
public class TimelineEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timeline_event_id")
    private Long timelineEventId;

    @Column(nullable = false)
    private Long dailyRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private TimelineEventType eventType;

    @Column(nullable = false)
    private LocalDateTime startAt;

    private LocalDateTime endAt;

    @Column(nullable = false)
    private String title;

    private String subtitle;

    @Column(columnDefinition = "TEXT")
    private String memo;

    protected TimelineEvent() {
    }

    private TimelineEvent(Long dailyRecordId, TimelineEventType eventType, LocalDateTime startAt,
                         LocalDateTime endAt, String title, String subtitle) {
        this.dailyRecordId = dailyRecordId;
        this.eventType = eventType;
        this.startAt = startAt;
        this.endAt = endAt;
        this.title = title;
        this.subtitle = subtitle;
    }

    public static TimelineEvent of(Long dailyRecordId, TimelineEventType eventType, LocalDateTime startAt,
                                  LocalDateTime endAt, String title, String subtitle) {
        return new TimelineEvent(dailyRecordId, eventType, startAt, endAt, title, subtitle);
    }

    /**
     * 사용자 편집으로 eventType/title/subtitle/startAt/endAt 필드를 절대값으로 교체한다(memo·하위 Item은 별도 처리).
     * eventType은 요청 누락 시 서비스가 현재 값을 병합해 non-null 목표값으로 전달한다.
     * 검증(필수·길이·시간 순서)은 서비스 계층({@code TimelineEventEditService}) 책임이고 엔티티는 대입만 한다.
     */
    public void updateDetails(TimelineEventType eventType, String title, String subtitle,
                              LocalDateTime startAt, LocalDateTime endAt) {
        this.eventType = eventType;
        this.title = title;
        this.subtitle = subtitle;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    /** 사용자 메모를 교체한다. {@code null}은 메모 제거다. 길이 검증은 서비스 계층 책임이다. */
    public void updateMemo(String memo) {
        this.memo = memo;
    }
}
