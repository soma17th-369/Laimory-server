package com.laimory.server.timeline.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 타임라인 이벤트. daily_record에 plain Long FK로 연결(@OneToMany 미사용 - 서비스=레포 1개 규칙 보존).
 * memo는 사용자가 나중에 작성하므로 생성 시점엔 비어 있다.
 */
@Entity
@Table(name = "timeline_events")
@Getter
public class TimelineEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timeline_event_id")
    private Long timelineEventId;

    @Column(nullable = false)
    private Long dailyRecordId;

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

    private TimelineEvent(Long dailyRecordId, LocalDateTime startAt, LocalDateTime endAt,
                         String title, String subtitle) {
        this.dailyRecordId = dailyRecordId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.title = title;
        this.subtitle = subtitle;
    }

    public static TimelineEvent of(Long dailyRecordId, LocalDateTime startAt, LocalDateTime endAt,
                                  String title, String subtitle) {
        return new TimelineEvent(dailyRecordId, startAt, endAt, title, subtitle);
    }

    /**
     * 사용자 편집으로 title/subtitle/startAt/endAt 4개 필드를 절대값으로 교체한다(memo·하위 Item 불변).
     * 검증(필수·길이·시간 순서)은 서비스 계층({@code TimelineEventEditService}) 책임이고 엔티티는 대입만 한다.
     */
    public void updateDetails(String title, String subtitle, LocalDateTime startAt, LocalDateTime endAt) {
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
