package com.laimory.server.timeline.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 타임라인 카드. daily_record에 plain Long FK로 연결(@OneToMany 미사용 - 서비스=레포 1개 규칙 보존).
 * memo는 사용자가 나중에 작성하므로 생성 시점엔 비어 있다.
 */
@Entity
@Table(name = "timeline_cards")
@Getter
public class TimelineCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    protected TimelineCard() {
    }

    private TimelineCard(Long dailyRecordId, LocalDateTime startAt, LocalDateTime endAt,
                         String title, String subtitle) {
        this.dailyRecordId = dailyRecordId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.title = title;
        this.subtitle = subtitle;
    }

    public static TimelineCard of(Long dailyRecordId, LocalDateTime startAt, LocalDateTime endAt,
                                  String title, String subtitle) {
        return new TimelineCard(dailyRecordId, startAt, endAt, title, subtitle);
    }
}
