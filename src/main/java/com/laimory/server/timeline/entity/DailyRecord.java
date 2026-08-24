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
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 일일 기록. (subject_id, record_date) 유일. draft 생성 시 status=DRAFT, emotion_type=NULL이며
 * 저장 API의 조건부 UPDATE가 emotion_type과 status=SAVED를 함께 최초 확정한다(과거 SAVED 행의
 * NULL은 정상값). 확정 후에는 SAVED 전용 감정 수정 PUT이 emotion_type만 교체한다(status 불변) —
 * DRAFT에 감정을 미리 쓰는 write 지점은 없다.
 */
@Entity
@Table(name = "daily_records")
@Getter
public class DailyRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "daily_record_id")
    private Long dailyRecordId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", nullable = false, length = 36)
    private UUID subjectId;

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

    private DailyRecord(UUID subjectId, LocalDate recordDate, LocalDateTime recordAt, String recordTimezone,
                        DailyRecordStatus status) {
        this.subjectId = subjectId;
        this.recordDate = recordDate;
        this.recordAt = recordAt;
        this.recordTimezone = recordTimezone;
        this.status = status;
    }

    public static DailyRecord createDraft(UUID subjectId, LocalDate recordDate, LocalDateTime recordAt,
                                          String recordTimezone) {
        return new DailyRecord(subjectId, recordDate, recordAt, recordTimezone, DailyRecordStatus.DRAFT);
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
