package com.laimory.server.timeline.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Event 장소 후보 한 개(#612). 장소가 애매한 Event에 AI가 우선순위 순서로 준 후보 장소명과 그 장소의 좌표(선택, #620)이며,
 * 사용자는 이 중에서 골라(또는 직접 입력해) Event {@code place}·좌표를 바꾼다. Event에 plain Long FK로 연결한다
 * (@ManyToOne 미사용). 좌표는 항상 함께 NULL이거나 함께 값이다(결과 저장 검증).
 *
 * <p>우선순위는 id 순서다 — AI 결과 저장이 받은 순서대로 INSERT하고 조회는 id 오름차순이다(순서 전용 컬럼 없음).
 * 생성 뒤 바뀌지 않으며, 장소를 지정해도 지우지 않는다. 수명은 Event와 같다(Event soft delete 때 삭제).
 */
@Entity
@Table(name = "timeline_event_place_candidates")
@Getter
public class TimelineEventPlaceCandidate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "timeline_event_place_candidate_id")
    private Long timelineEventPlaceCandidateId;

    @Column(nullable = false)
    private Long timelineEventId;

    @Column(nullable = false)
    private String name;

    private Double latitude;

    private Double longitude;

    protected TimelineEventPlaceCandidate() {
    }

    private TimelineEventPlaceCandidate(Long timelineEventId, String name, Double latitude, Double longitude) {
        this.timelineEventId = timelineEventId;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public static TimelineEventPlaceCandidate of(Long timelineEventId, String name, Double latitude, Double longitude) {
        return new TimelineEventPlaceCandidate(timelineEventId, name, latitude, longitude);
    }
}
