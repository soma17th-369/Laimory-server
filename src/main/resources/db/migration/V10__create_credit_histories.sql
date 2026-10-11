-- #565: 크레딧 사용 1건당 1행의 상태 전이 기록. 요청 시 차감(CHARGED)한 뒤 결과 저장이면 CONSUMED, 생성 실패·
-- 유실이면 REFUNDED로 한 번 전이한다. 환불 근거와 금액(amount = 실제 차감한 양)의 단일 기준이다 — 비용 변경
-- migration이 진행 중 생성의 환불액을 바꾸지 않는다. 범용 원장(지급·조정)은 두지 않는다.
-- 대상은 cost_type + reference_id(TIMELINE_CREATION이면 taskId)로 식별해 timeline 도메인에 종속되지 않는다.
-- mapping 삭제가 기록을 암묵 cascade하지 않게 RESTRICT(subject_credits 선례 — 물리 삭제는 #302 소유).
-- additive CREATE TABLE이지만 RESTRICT FK 때문에 이 기록 삭제를 모르는 구 image의 탈퇴 worker는 기록 행이 있는
-- subject의 mapping 삭제에 실패한다(job 보존·다음 날 재시도 — V6과 같은 수용한 결정).
CREATE TABLE credit_histories (
    credit_history_id BIGINT NOT NULL AUTO_INCREMENT,
    subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- enum literal exact-match 식별자는 컬럼 단위 binary collation(credit_costs.type 선례).
    cost_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- CreditCostType literal(무엇에 썼나)
    reference_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- 쓴 대상의 id
    amount INT NOT NULL, -- 차감한 양. 환불은 이 값으로 한다
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- CHARGED|CONSUMED|REFUNDED
    refund_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL, -- REFUNDED일 때만
    -- 감사 컬럼 (BaseEntity; native INSERT·JPQL 전이가 앱 Seoul 벽시계로 직접 채움)
    created_at DATETIME(6) NOT NULL, -- = 차감 시각
    updated_at DATETIME(6) NOT NULL, -- = 소비/환불 전이 시각
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (credit_history_id),
    UNIQUE KEY uk_credit_histories_reference (cost_type, reference_id), -- 한 작업에 한 행
    KEY idx_credit_histories_subject (subject_id),
    CONSTRAINT chk_credit_histories_amount_non_negative CHECK (amount >= 0),
    CONSTRAINT chk_credit_histories_refund_reason CHECK ((status = 'REFUNDED') = (refund_reason IS NOT NULL)),
    CONSTRAINT fk_credit_histories_subject
        FOREIGN KEY (subject_id) REFERENCES user_subject_links (subject_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
