package com.laimory.server.timeline.entity;

import com.laimory.server.common.BaseEntity;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;

/**
 * 일일 기록. (user_id, record_date) 유일. draft 생성 시 status=DRAFT, emotion_type=NULL.
 */
@Entity
@Table(name = "daily_records")
@Getter
public class DailyRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "daily_record_id")
    private Long dailyRecordId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate recordDate;

    @Column(name = "record_timezone", nullable = false, length = 64)
    private String recordTimezone;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private EmotionType emotionType;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private DailyRecordStatus status;

    protected DailyRecord() {
    }

    private DailyRecord(Long userId, LocalDate recordDate, String recordTimezone, DailyRecordStatus status) {
        this.userId = userId;
        this.recordDate = recordDate;
        this.recordTimezone = recordTimezone;
        this.status = status;
    }

    public static DailyRecord createDraft(Long userId, LocalDate recordDate, String recordTimezone) {
        return new DailyRecord(userId, recordDate, recordTimezone, DailyRecordStatus.DRAFT);
    }
}
