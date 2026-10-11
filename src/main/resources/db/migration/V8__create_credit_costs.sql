-- #558: 크레딧 소비 기능별 비용. 비용의 단일 기준을 코드 상수에서 이 테이블로 옮긴다 — 모든 서버가 같은 행을
-- 읽으므로 rolling 배포 중에도 앱 고지·사전 검사·차감이 서버마다 갈리지 않는다. 서버별 캐시는 두지 않는다.
-- 값 변경은 새 migration의 UPDATE로만 한다(PR 리뷰·이력이 남고 적용 순간부터 전 서버가 같은 값을 쓴다).
-- 직접 운영 SQL은 규칙으로 금지한다(기술 차단 없음).
-- additive CREATE + 현재 상수와 같은 값의 seed라 구 앱(상수 1)과 공존해도 비용이 달라지지 않는다.
CREATE TABLE credit_costs (
    -- type은 enum literal exact-match 식별자다 → 컬럼 단위 binary collation(term_documents.term_type 선례).
    -- 테이블 기본 _unicode_ci면 소문자 오타 seed가 enum literal 조회에 case-insensitive 매칭돼
    -- @Enumerated hydration을 500으로 깨뜨린다.
    type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- 현재 CreditCostType literal 1종
    cost INT NOT NULL, -- 0 = 무료
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (type),
    CONSTRAINT chk_credit_costs_cost_non_negative CHECK (cost >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 1은 직전 코드 상수 CreditCost.TIMELINE_CREATION(#555)의 사본이다 — 구 앱과 공존하는 cutover 중 비용 변화 없음.
-- DB 세션은 UTC라 감사 컬럼은 이 저장소의 Asia/Seoul 벽시계 계약에 맞춰 offset으로 변환한다(V6 선례).
INSERT INTO credit_costs (type, cost, created_at, updated_at)
VALUES ('TIMELINE_CREATION',
        1,
        CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'),
        CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'));
