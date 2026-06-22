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
    emotion_type VARCHAR(32) NULL,                  -- 별도 save(DRAFT->SAVED)에서 설정
    status VARCHAR(32) NOT NULL,                     -- DRAFT|SAVED
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by_type VARCHAR(32) NOT NULL,
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
    modified_by_type VARCHAR(32) NOT NULL,
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
    modified_by_type VARCHAR(32) NOT NULL,
    PRIMARY KEY (timeline_item_id),
    KEY idx_timeline_items_event (timeline_event_id),
    KEY idx_timeline_items_type (item_type),
    CONSTRAINT fk_timeline_items_event
        FOREIGN KEY (timeline_event_id) REFERENCES timeline_events (timeline_event_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
