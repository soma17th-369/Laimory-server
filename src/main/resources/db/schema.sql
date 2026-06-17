-- Laimory timeline 도메인 DDL (MySQL 8). ddl-auto=validate 이므로 앱 기동 전에 적용되어 있어야 한다.
-- 운영/스테이징: 수동 적용. 로컬: docker-compose가 /docker-entrypoint-initdb.d/ 로 첫 기동 시 자동 적용.

CREATE TABLE IF NOT EXISTS daily_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    record_date DATE NOT NULL,
    emotion_type VARCHAR(32) NULL,                  -- 별도 save(DRAFT->SAVED)에서 설정
    status VARCHAR(32) NOT NULL,                     -- DRAFT|SAVED
    PRIMARY KEY (id),
    UNIQUE KEY uq_daily_records_user_date (user_id, record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS timeline_cards (
    id BIGINT NOT NULL AUTO_INCREMENT,
    daily_record_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NULL,
    title VARCHAR(255) NOT NULL,                     -- 검증에서 title 필수
    subtitle VARCHAR(255) NULL,
    memo TEXT NULL,
    PRIMARY KEY (id),
    KEY idx_timeline_cards_daily_record (daily_record_id),
    CONSTRAINT fk_timeline_cards_daily_record
        FOREIGN KEY (daily_record_id) REFERENCES daily_records (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS timeline_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    timeline_card_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NULL,
    payload JSON NOT NULL,                           -- 타입은 payload 안 itemType(discriminator)에. 검색 필요 시 generated column 후속 추가
    PRIMARY KEY (id),
    KEY idx_timeline_items_card (timeline_card_id),
    CONSTRAINT fk_timeline_items_card
        FOREIGN KEY (timeline_card_id) REFERENCES timeline_cards (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
