-- #548: subject별 크레딧 잔액. 타임라인 전용이 아닌 범용 재화이며 가입 transaction이 기본 60으로 행을 만든다.
-- 차감은 소비한 기능의 transaction 안에서 조건부 UPDATE(remaining > 0)로 하므로 음수가 되지 않는다 — CHECK는
-- 수동 운영 SQL 실수까지 막는 마지막 방어다. 기본값의 권위는 애플리케이션 상수라 DB DEFAULT는 두지 않는다.
-- mapping 삭제가 잔액을 암묵 cascade하지 않게 RESTRICT(user_memories 선례 — 물리 삭제는 #302 소유).
-- additive CREATE TABLE이지만 RESTRICT FK 때문에 크레딧 삭제를 모르는 구 image의 탈퇴 worker는 mapping 삭제에
-- 실패한다(job 보존·다음 날 재시도 — 수용한 결정).
CREATE TABLE subject_credits (
    subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    remaining INT NOT NULL,
    -- 감사 컬럼 (BaseEntity; native insert-if-absent가 timestamp를 직접 채움)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (subject_id),
    CONSTRAINT chk_subject_credits_remaining_non_negative CHECK (remaining >= 0),
    CONSTRAINT fk_subject_credits_subject
        FOREIGN KEY (subject_id) REFERENCES user_subject_links (subject_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 기존 subject 전원에게 기본 60 지급(rollout ①단계). 60은 이 시점 애플리케이션 상수의 사본이다.
-- user_subject_links에는 회원 상태가 없어 탈퇴 대기 subject도 행을 받는다 — 탈퇴 삭제가 함께 지운다.
-- DB 세션은 UTC라 감사 컬럼은 이 저장소의 Asia/Seoul 벽시계 계약에 맞춰 offset으로 변환한다(tz 테이블 불필요).
INSERT IGNORE INTO subject_credits (subject_id, remaining, created_at, updated_at)
SELECT subject_id,
       60,
       CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'),
       CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00')
FROM user_subject_links;
