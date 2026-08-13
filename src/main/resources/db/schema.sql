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

-- 인증 사용자 ↔ 콘텐츠 subject 매핑(#282, 계획 §2.3). raw user_id를 저장하지 않는다 — PK는
-- 애플리케이션 HMAC-SHA-256("content-subject-lookup:v1" || userId 8-byte BE) 결과라, DB 단독
-- 유출로는 후보 userId 대입이 어렵다. 감사 컬럼·auto-increment surrogate·정밀 생성 시각을 의도적으로
-- 두지 않는다(행 자체가 최소 정보 원칙 대상). rotation은 PK·lookup_key_version만 원자 교체한다(subject 불변).
-- 콘텐츠 테이블의 subject FK가 참조하므로 파일 안에서 그 테이블들보다 먼저 정의한다.
CREATE TABLE IF NOT EXISTS user_subject_links (
    user_lookup_key BINARY(32) NOT NULL,             -- HMAC-SHA-256 lookup key(Secrets Manager 비밀키 기반)
    subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- canonical lowercase UUIDv4
    lookup_key_version SMALLINT NOT NULL,            -- HMAC key rotation 식별(secret currentVersion)
    PRIMARY KEY (user_lookup_key),
    UNIQUE KEY uq_user_subject_links_subject (subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- subject_id가 콘텐츠 owner authority다.
CREATE TABLE IF NOT EXISTS daily_records (
    daily_record_id BIGINT NOT NULL AUTO_INCREMENT,
    subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- 콘텐츠 owner authority
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
    UNIQUE KEY uq_daily_records_subject_date (subject_id, record_date),
    -- mapping 삭제가 콘텐츠를 암묵 cascade하지 않게 RESTRICT(계획 §2.4 — 탈퇴는 콘텐츠 명시 삭제 후 mapping 삭제).
    CONSTRAINT fk_daily_records_subject
        FOREIGN KEY (subject_id) REFERENCES user_subject_links (subject_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 감사 컬럼 default: final 테이블 writer는 API JPA 하나뿐이다(AI 결과도 서버 transaction이 저장한다).
-- timestamp DB default는 과거 AI raw INSERT 계약의 잔재로 남겨 둔다(live DDL 변경 없이 무해).
CREATE TABLE IF NOT EXISTS timeline_events (
    timeline_event_id BIGINT NOT NULL AUTO_INCREMENT,
    daily_record_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN', -- TimelineEventType literal. default는 기존 행 backfill·구버전 writer 컬럼 생략 호환용(live DDL과 동일 계약)
    start_at DATETIME NOT NULL,
    end_at DATETIME NULL,
    title VARCHAR(255) NOT NULL,                     -- 검증에서 title 필수
    subtitle VARCHAR(255) NULL,
    question VARCHAR(255) NULL,                      -- AI가 Event마다 생성한 질문. 기존 행은 backfill하지 않고 NULL 유지
    memo TEXT NULL,
    -- 감사 컬럼 (BaseEntity + AI raw INSERT용 DB default)
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (timeline_event_id),
    KEY idx_timeline_events_daily_record (daily_record_id),
    CONSTRAINT fk_timeline_events_daily_record
        FOREIGN KEY (daily_record_id) REFERENCES daily_records (daily_record_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Item은 Event가 아닌 독립 행이다. 하루 범위는 timeline_event_items(junction)→timeline_events→daily_records로
-- 해석하며 daily_record_id FK를 두지 않는다. rawId 중복은 DB가 거부하지 않는다(같은 record 안 중복 방지는
-- API 사전 제외 + AI write 직전 재검사의 application-level 방어 — race/legacy 중복 허용이 팀 결정).
CREATE TABLE IF NOT EXISTS timeline_items (
    timeline_item_id BIGINT NOT NULL AUTO_INCREMENT,
    item_type VARCHAR(32) NOT NULL,                  -- 타입 권위(payload 밖). payload JSON엔 타입 정보 없음
    -- rawId는 대소문자 구분 opaque 식별자 → 컬럼 단위 binary collation으로 정확 비교(테이블 기본 _unicode_ci와 달리).
    -- 서버 dedupe(Java String)·기존 rawId 제외(HashSet/IN)와 DB 비교 규칙을 일치시킨다(불일치 시 abc/ABC로 제외 어긋남).
    raw_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, -- 클라 원본 데이터 ID(UUIDv7). envelope 필드, 서버는 해석 없이 echo
    start_at DATETIME NULL,                           -- nullable: 시간 미상 아이템 허용
    end_at DATETIME NULL,
    payload JSON NOT NULL,                           -- 타입 정보 없는 raw JSON. 검색 필요 시 generated column 후속 추가
    -- 감사 컬럼 (BaseEntity + AI raw INSERT용 DB default)
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (timeline_item_id),
    KEY idx_timeline_items_type (item_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Event↔Item N:M junction. 같은 record 안에서만 Item을 공유한다는 규칙은 DB 제약이 아니라 writer 계약이다
-- (AI는 새 Item을 현재 task의 새 Event에만 연결하고, Event PATCH는 같은 record PHOTO를 재사용할 수 있음).
-- Event/Item 행 삭제 시 자기 junction은 FK cascade로 지워지고, Event에서 PHOTO Item 연결 해제는
-- junction 행만 명시적으로 지운다. association이 0이 된 Item은 삭제 흐름이 정리한다(감사 컬럼 없음 — 순수 연결 행).
CREATE TABLE IF NOT EXISTS timeline_event_items (
    timeline_event_id BIGINT NOT NULL,
    timeline_item_id  BIGINT NOT NULL,
    PRIMARY KEY (timeline_event_id, timeline_item_id),
    KEY idx_event_items_item (timeline_item_id, timeline_event_id),
    CONSTRAINT fk_event_items_event
        FOREIGN KEY (timeline_event_id) REFERENCES timeline_events (timeline_event_id) ON DELETE CASCADE,
    CONSTRAINT fk_event_items_item
        FOREIGN KEY (timeline_item_id) REFERENCES timeline_items (timeline_item_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 마지막 Event 참조가 사라지는 PHOTO의 S3 삭제 의무와 원문 Item을 MySQL commit과 함께 보존하는 작업 테이블.
-- 원 TimelineItem은 job이 존재하는 동안 남고, worker가 S3 성공 뒤 job→Item 순서로 한 transaction에서 지운다.
-- 행 존재가 처리 대기 상태다. available_at은 여러 process/thread의 같은 날 중복 선택을 막고,
-- 실패·crash 행을 다음 일일 실행에서 다시 선택하기 위한 최소 eligibility 표시다.
CREATE TABLE IF NOT EXISTS timeline_photo_delete_jobs (
    timeline_photo_delete_job_id BIGINT NOT NULL AUTO_INCREMENT,
    timeline_item_id BIGINT NOT NULL,
    object_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- 감사 컬럼 (BaseEntity; native insert-if-absent가 timestamp를 직접 채움)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (timeline_photo_delete_job_id),
    UNIQUE KEY uq_timeline_photo_delete_jobs_item (timeline_item_id),
    UNIQUE KEY uq_timeline_photo_delete_jobs_object (object_key),
    KEY idx_timeline_photo_delete_jobs_available
        (available_at, created_at, timeline_photo_delete_job_id),
    CONSTRAINT fk_timeline_photo_delete_jobs_item
        FOREIGN KEY (timeline_item_id) REFERENCES timeline_items (timeline_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- API→AI 입력 staging(app↔AI 데이터 교환 경유). AI가 taskId로 읽고, final transaction에서 채택한 행만
-- DELETE한다(omitted 행은 retention cleanup이 정리). (task_id, raw_id) unique는 task 안 rawId 중복을
-- DB에서 차단한다(API 요청 dedupe의 백스톱).
CREATE TABLE IF NOT EXISTS timeline_draft_source_items (
    timeline_draft_source_item_id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(36) NOT NULL,
    subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- 콘텐츠 owner authority
    item_type VARCHAR(32) NOT NULL,                  -- 타입 권위(payload 밖). client discriminator 그대로
    -- rawId는 대소문자 구분 opaque 식별자 → binary collation(테이블 기본 _unicode_ci와 달리). 아래 (task_id, raw_id)
    -- UNIQUE가 이 collation을 따라 case-sensitive 비교하므로 서버 Java dedupe(abc≠ABC)와 규칙이 일치한다.
    raw_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, -- 클라 원본 데이터 ID(UUIDv7). envelope 필드, 서버는 해석 없이 echo
    start_at DATETIME NULL,                          -- nullable: 시간 미상 아이템 허용
    end_at DATETIME NULL,
    payload JSON NOT NULL,                           -- 타입 정보 없는 raw JSON
    -- 여러 process/thread cleanup의 같은 날 중복 선택 방지 + 실패 행의 다음 일일 실행 재시도 시각.
    cleanup_available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (timeline_draft_source_item_id),
    UNIQUE KEY uq_draft_source_task_raw (task_id, raw_id), -- leftmost prefix가 task_id 조회 index를 겸한다
    KEY idx_draft_source_created (created_at),
    KEY idx_draft_source_cleanup
        (cleanup_available_at, created_at, timeline_draft_source_item_id),
    -- FK가 만드는 implicit index 외 별도 조회 index는 두지 않는다(조회는 task_id 경유).
    CONSTRAINT fk_draft_source_items_subject
        FOREIGN KEY (subject_id) REFERENCES user_subject_links (subject_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 소셜 로그인 사용자. 유일성은 (provider, provider_user_id)로만 — email 병합 금지(Kakao email null 허용).
CREATE TABLE IF NOT EXISTS users (
    user_id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(32) NOT NULL,                   -- GOOGLE|KAKAO
    provider_user_id VARCHAR(255) NOT NULL,          -- OIDC id_token의 sub
    email VARCHAR(255) NULL,                         -- Kakao는 미동의 시 NULL
    nickname VARCHAR(100) NULL,
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uq_users_provider_user (provider, provider_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- User Memory 정본. subject_id가 owner PK다.
CREATE TABLE IF NOT EXISTS user_memories (
    subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    memory JSON NOT NULL,                            -- 서버는 내부 구조·버전을 해석하지 않는다(전체 교체만)
    -- 감사 컬럼 (BaseEntity + native upsert용 직접 기입)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (subject_id),
    -- mapping 삭제가 문서를 암묵 cascade하지 않게 RESTRICT(계획 §2.4 — 탈퇴는 콘텐츠 명시 삭제 후 mapping 삭제).
    CONSTRAINT fk_user_memories_subject
        FOREIGN KEY (subject_id) REFERENCES user_subject_links (subject_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- refresh token(원문 미저장 — SHA-256 hex 해시만). FK 없음(기존 방침), parent_id는 회전 계보 감사용 soft ref.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    refresh_token_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,                 -- sha256 hex(항상 64자 — CHAR가 아닌 VARCHAR인 이유: ddl-auto=validate가 String 매핑에 VARCHAR를 요구)
    status VARCHAR(32) NOT NULL,                     -- ACTIVE|ROTATED|REVOKED
    parent_id BIGINT NULL,                           -- 회전 이전 토큰(soft ref, 감사용)
    expires_at DATETIME(6) NOT NULL,
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (refresh_token_id),
    UNIQUE KEY uq_refresh_tokens_token_hash (token_hash),
    KEY idx_refresh_tokens_user (user_id)            -- 재사용 탐지 시 사용자 전체 폐기 스캔용
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- FCM 푸시 등록(사용자 1:N 앱 설치). Firebase Installation ID(FID)가 발송 target이며 행 존재 = 활성 등록
-- (해제·영구 무효는 행 삭제). FID는 대소문자 구분 opaque 식별자 → 테이블 기본(_unicode_ci)과 달리
-- 컬럼 단위 binary collation으로 정확 비교. subject_id FK 없음(사용자 보조 데이터 기존 방침).
-- 쓰기는 native upsert(등록·계정 전환 재결합 원자화 — JPA auditing 미적용, 감사 컬럼은 upsert가 직접 채움).
CREATE TABLE IF NOT EXISTS push_registrations (
    push_registration_id BIGINT NOT NULL AUTO_INCREMENT,
    subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- owner authority. 기존 soft-owner 방침대로 FK 없음
    firebase_installation_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    last_registered_at DATETIME(6) NOT NULL,         -- Android가 FID를 서버와 마지막으로 동기화한 시각(후속 stale 정리 기준)
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (push_registration_id),
    UNIQUE KEY uq_push_registrations_fid (firebase_installation_id),
    KEY idx_push_registrations_subject (subject_id)  -- subject의 활성 설치 전체 발송 조회용
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 기본 app_config 시드: /intro(AppConfig 조회)는 config row 존재를 요구하므로,
-- 신규 DB(마이그레이션/로컬)에서 없으면 1건 생성한다(멱등 — 이미 있으면 no-op).
INSERT INTO app_config (min_app_version, recommend_app_version)
SELECT 1, 1 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_config);
