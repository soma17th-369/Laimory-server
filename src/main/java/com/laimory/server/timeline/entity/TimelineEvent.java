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
 * 생성 경로는 둘이다 — AI 결과 저장은 question/place/address/좌표를 채울 수 있고 memo는 비워 두며,
 * 수동 생성(POST .../daily-records/{recordDate}/events)은 question/address가 항상 null이고
 * optional place·좌표·memo를 함께 받을 수 있다. 생성 뒤 {@code place}는 장소 지정 API로, 좌표는
 * Event PATCH·장소 지정 API로 바꿀 수 있다(#616).
 *
 * <p>{@code @DynamicUpdate}: 통합 PATCH에서 memo가 생략된 요청과 memo PUT이 서로 다른 필드 그룹을
 * 갱신할 수 있다. Hibernate 기본 UPDATE는 모든 updatable 컬럼을 SET에 포함하므로, 두 요청이 같은 row를 읽고
 * 순차 커밋하면 나중 커밋이 상대의 변경을 자신의 로드 시점 스냅샷으로 되돌린다(교차-필드 lost update).
 * dynamic update는 실제 변경된 컬럼만 SET해 이 경로를 제거한다(같은 필드 동시 수정은 여전히
 * last-write-wins — 필드 그룹이 겹치지 않는 현 계약에서는 충분하다). 좌표는 PATCH와 장소 지정 PUT이 함께
 * 쓰는 그룹이라, 같은 Event에 두 요청이 겹치고 한쪽의 위도나 경도 하나가 기존 값과 같으면 두 요청의 값이
 * 섞인 좌표가 남을 수 있다 — 한 사용자의 동시 편집이라 막지 않는다(#616). 비용은 flush 시 SQL 동적 생성.
 */
@Entity
@Table(name = "timeline_events")
@DynamicUpdate
@Getter
public class TimelineEvent extends BaseEntity {

    /** subtitle 안의 장소 자리 표시. DB에는 그대로 두고, 표시·User Memory 전달 때 {@code place}로 채운다(#612). */
    public static final String PLACE_PLACEHOLDER = "{place}";

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

    /** AI가 Event마다 생성한 질문. 사용자 편집 대상이 아니며 AI 결과 저장에서만 채워진다(없으면 null). */
    private String question;

    /**
     * 이 Event의 단일 장소명. source item payload의 복수 {@code places}와 이름·의미가 다른 Event 결과 필드다.
     * AI 결과 저장이 초기값을 채우고(장소 후보가 있으면 1번 후보), 사용자가 장소 지정 API로 바꿀 수 있다(없으면 null).
     * subtitle의 자리 표시 {@code {place}}는 표시 시점에 이 값으로 채워진다(#612).
     */
    private String place;

    /** 이 Event의 주소 문자열. 사용자 편집 대상이 아니며 AI 결과 저장에서만 채워진다(없으면 null). */
    private String address;

    /**
     * Event 대표 좌표. AI 결과 저장이 관측 위치(이동 Event는 도착지)로 초기값을 채우고(#612), 사용자가 수동 생성·
     * PATCH·장소 지정에서 보낸 좌표로 바뀐다(#616). 위도·경도는 항상 함께 null이거나 함께 값이다.
     */
    private Double latitude;

    private Double longitude;

    @Column(columnDefinition = "TEXT")
    private String memo;

    /**
     * soft delete 시각. NULL이면 활성이다. 조건부 bulk UPDATE만 쓴다 — Event 단건 삭제, 또는 소속 DailyRecord 삭제
     * 시 record와 같은 시각(이미 삭제된 Event는 원래 시각 유지). 삭제 때 place·address·좌표는 함께 NULL이 된다.
     */
    private LocalDateTime deletedAt;

    protected TimelineEvent() {
    }

    private TimelineEvent(Long dailyRecordId, TimelineEventType eventType, LocalDateTime startAt,
                         LocalDateTime endAt, String title, String subtitle, String question,
                         String place, String address, Double latitude, Double longitude) {
        this.dailyRecordId = dailyRecordId;
        this.eventType = eventType;
        this.startAt = startAt;
        this.endAt = endAt;
        this.title = title;
        this.subtitle = subtitle;
        this.question = question;
        this.place = place;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public static TimelineEvent of(Long dailyRecordId, TimelineEventType eventType, LocalDateTime startAt,
                                  LocalDateTime endAt, String title, String subtitle, String question,
                                  String place, String address, Double latitude, Double longitude) {
        return new TimelineEvent(dailyRecordId, eventType, startAt, endAt, title, subtitle, question,
                place, address, latitude, longitude);
    }

    /**
     * 사용자 편집으로 eventType/title/subtitle/startAt/endAt 필드를 절대값으로 교체한다(memo·하위 Item은 별도 처리).
     * AI 결과 필드(question·place·address·좌표)는 이 경로가 건드리지 않는다(좌표는 {@link #updateCoordinates}).
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

    /** 장소명만 교체한다(#612) — title·subtitle·좌표·address·장소 후보는 그대로다. 검증은 서비스 계층 책임이다. */
    public void updatePlace(String place) {
        this.place = place;
    }

    /** 사용자가 지정한 좌표로 위도·경도를 함께 교체한다(#616). 쌍·범위 검증은 서비스 계층 책임이다. */
    public void updateCoordinates(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /** 사용자 메모를 교체한다. {@code null}은 메모 제거다. 길이 검증은 서비스 계층 책임이다. */
    public void updateMemo(String memo) {
        this.memo = memo;
    }
}
