-- #612: AI가 Event마다 주는 대표 좌표(사용자가 실제로 있던 관측 위치)와 장소 후보를 저장한다.
-- additive 컬럼·테이블이라 구 앱과 호환된다(rolling 배포 중 실행 가능). 구 앱 insert는 좌표를 생략해 NULL이 된다.
--
-- 좌표는 POINT가 아니라 DOUBLE 2컬럼이다. 공간 검색이 없고 nullable이라 SPATIAL INDEX를 걸 수 없다.
-- 둘은 항상 함께 NULL이거나 함께 값이다(앱 검증 — AI 결과의 반쪽 좌표는 400).
ALTER TABLE timeline_events
    ADD COLUMN latitude DOUBLE NULL,
    ADD COLUMN longitude DOUBLE NULL,
    ALGORITHM=INSTANT;

-- 장소 후보는 후보 1개 = 1행이다. 순서 = AI가 보낸 우선순위이며 받은 순서대로 INSERT하고 ORDER BY id로 읽는다
-- (순서 전용 컬럼 없음). 수명은 Event와 같다 — Event soft delete가 hard DELETE하고, 탈퇴 삭제(#302)는
-- daily_records → timeline_events → 후보로 FK CASCADE된다.
CREATE TABLE timeline_event_place_candidates (
    timeline_event_place_candidate_id BIGINT NOT NULL AUTO_INCREMENT,
    timeline_event_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (timeline_event_place_candidate_id),
    -- FK용이자 Event 묶음 조회(IN)·ORDER BY id 겸용
    KEY idx_timeline_event_place_candidates_event (timeline_event_id),
    CONSTRAINT fk_timeline_event_place_candidates_event
        FOREIGN KEY (timeline_event_id) REFERENCES timeline_events (timeline_event_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
