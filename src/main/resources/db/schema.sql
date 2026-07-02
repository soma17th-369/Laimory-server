-- Laimory timeline 도메인 DDL (MySQL 8). ddl-auto=validate 이므로 앱 기동 전에 적용되어 있어야 한다.
-- 운영/스테이징: 수동 적용. 로컬: docker-compose가 /docker-entrypoint-initdb.d/ 로 첫 기동 시 자동 적용.
-- ddl-auto=validate는 전체 엔티티를 검증하므로 로컬 DB엔 app_config 등 기존 테이블도 있어야 한다.

-- 기존 appconfig 도메인 테이블 (AppConfig 엔티티 대응)
CREATE TABLE IF NOT EXISTS app_config (
    app_config_id BIGINT NOT NULL AUTO_INCREMENT,
    min_app_version BIGINT NULL,
    recommend_app_version BIGINT NULL,
    debug_test_message VARCHAR(255) NULL,
    PRIMARY KEY (app_config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS daily_records (
    daily_record_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    record_date DATE NOT NULL,
    record_at DATETIME NOT NULL,                     -- 클라가 보낸 기록 벽시계 시각(같은 날 여러 task면 마지막에 finalize된 값). record_timezone과 짝지어 절대시각 복원
    record_timezone VARCHAR(64) NOT NULL,           -- record_at·이벤트/아이템 wall-clock을 절대시각으로 해석할 zone
    emotion_type VARCHAR(32) NULL,                  -- 별도 save(DRAFT->SAVED)에서 설정
    status VARCHAR(32) NOT NULL,                     -- DRAFT|SAVED
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (daily_record_id),
    UNIQUE KEY uq_daily_records_user_date (user_id, record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS timeline_events (
    timeline_event_id BIGINT NOT NULL AUTO_INCREMENT,
    daily_record_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NULL,
    title VARCHAR(255) NOT NULL,                     -- 검증에서 title 필수
    subtitle VARCHAR(255) NULL,
    memo TEXT NULL,
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (timeline_event_id),
    KEY idx_timeline_events_daily_record (daily_record_id),
    CONSTRAINT fk_timeline_events_daily_record
        FOREIGN KEY (daily_record_id) REFERENCES daily_records (daily_record_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS timeline_items (
    timeline_item_id BIGINT NOT NULL AUTO_INCREMENT,
    timeline_event_id BIGINT NOT NULL,
    item_type VARCHAR(32) NOT NULL,                  -- 타입 권위(payload 밖). payload JSON엔 타입 정보 없음
    start_at DATETIME NULL,                           -- nullable: 시간 미상 아이템 허용
    end_at DATETIME NULL,
    payload JSON NOT NULL,                           -- 타입 정보 없는 raw JSON. 검색 필요 시 generated column 후속 추가
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (timeline_item_id),
    KEY idx_timeline_items_event (timeline_event_id),
    KEY idx_timeline_items_type (item_type),
    CONSTRAINT fk_timeline_items_event
        FOREIGN KEY (timeline_event_id) REFERENCES timeline_events (timeline_event_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- AI draft 작업의 원본 source item(app↔AI 데이터 교환 경유). 콜백 finalize 시 timeline_items로 옮기고 삭제.
CREATE TABLE IF NOT EXISTS timeline_draft_source_items (
    timeline_draft_source_item_id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    item_type VARCHAR(32) NOT NULL,                  -- 타입 권위(payload 밖). client discriminator 그대로
    start_at DATETIME NULL,                          -- nullable: 시간 미상 아이템 허용
    end_at DATETIME NULL,
    payload JSON NOT NULL,                           -- 타입 정보 없는 raw JSON
    timeline_draft_event_suggestion_id BIGINT NULL,  -- AI가 그루핑 시 UPDATE로 채우는 소속 이벤트(soft ref, finalize에서 앱-레벨 검증). 하드 FK 아님(두 staging 독립 삭제)
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (timeline_draft_source_item_id),
    KEY idx_draft_source_task (task_id),
    KEY idx_draft_source_created (created_at)        -- cleanup 보관기간 스캔용
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- AI가 콜백 전 write-then-notify로 저장하는 이벤트 제안(메타만). finalize 시 timeline_events로 옮기고 삭제.
-- app→AI 입력(timeline_draft_source_items)과 대칭인 AI→API 출력 staging. 각 이벤트에 묶이는 source item은
-- timeline_draft_source_items.timeline_draft_event_suggestion_id(soft ref)로 가리킨다(1:N, FK는 item 쪽).
-- 이 테이블은 AI가 raw INSERT(JPA auditing 없음)하고 cleanup이 created_at에 의존하므로 감사 컬럼에 DB default를 준다.
CREATE TABLE IF NOT EXISTS timeline_draft_event_suggestions (
    timeline_draft_event_suggestion_id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,                       -- 이벤트 시작(timeline_events.start_at NOT NULL 대응)
    end_at DATETIME NULL,
    title VARCHAR(255) NOT NULL,
    subtitle VARCHAR(255) NULL,
    -- 감사 컬럼: 외부 writer(AI)가 raw INSERT하므로 DB default로 자동 채움(기존 도메인 테이블은 API JPA가 채워 default 없음)
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (timeline_draft_event_suggestion_id),
    KEY idx_draft_event_task (task_id),
    KEY idx_draft_event_created (created_at)          -- cleanup 보관기간 스캔용
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 기본 app_config 시드: /intro(AppConfig 조회)는 config row 존재를 요구하므로,
-- 신규 DB(마이그레이션/로컬)에서 없으면 1건 생성한다(멱등 — 이미 있으면 no-op).
INSERT INTO app_config (min_app_version, recommend_app_version)
SELECT 1, 1 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_config);
