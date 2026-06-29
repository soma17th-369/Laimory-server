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
import java.time.LocalDateTime;
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

    @Column(name = "record_at", nullable = false)
    private LocalDateTime recordAt;

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

    private DailyRecord(Long userId, LocalDate recordDate, LocalDateTime recordAt, String recordTimezone,
                        DailyRecordStatus status) {
        this.userId = userId;
        this.recordDate = recordDate;
        this.recordAt = recordAt;
        this.recordTimezone = recordTimezone;
        this.status = status;
    }

    public static DailyRecord createDraft(Long userId, LocalDate recordDate, LocalDateTime recordAt,
                                          String recordTimezone) {
        return new DailyRecord(userId, recordDate, recordAt, recordTimezone, DailyRecordStatus.DRAFT);
    }

    /**
     * 같은 날짜 재기록(append) 시 record_at/record_timezone을 이번 finalize의 값으로 갱신한다.
     * 같은 날 task가 여럿이면 마지막에 finalize(=DB write)된 값이 남는다(콜백 도착 순서 기준이라 POST 순서와 다를 수 있음).
     * 둘은 절대시각 해석의 짝이므로 함께 갱신한다.
     */
    public void updateRecordTime(LocalDateTime recordAt, String recordTimezone) {
        this.recordAt = recordAt;
        this.recordTimezone = recordTimezone;
    }
}
