-- #568: 하루 기록·Event 삭제를 soft delete로 바꾼다. 삭제는 행을 지우지 않고 deleted_at을 찍으며(NULL = 활성),
-- 앱의 모든 조회는 활성 행만 본다. 삭제된 행은 분석용으로 회원 탈퇴 시까지 남고 계정 삭제(#302)가 hard delete한다.
-- additive 컬럼이라 구 앱과 호환된다(rolling 배포 중 실행 가능). 구 앱 insert는 deleted_at을 생략해 활성 행이 된다.
--
-- 문장마다 ALGORITHM을 명시한다. VIRTUAL 컬럼 추가를 다른 ALTER 동작과 한 문장에 섞으면 INPLACE가 불가능해
-- MySQL이 조용히 COPY(테이블 재작성, 그동안 쓰기 차단)를 고른다. 명시하면 online으로 못 할 때 실패한다.
ALTER TABLE timeline_events ADD COLUMN deleted_at DATETIME(6) NULL, ALGORITHM=INSTANT;

ALTER TABLE daily_records ADD COLUMN deleted_at DATETIME(6) NULL, ALGORITHM=INSTANT;

-- 같은 날짜 유일성을 활성 행에만 건다. 활성 행은 1, 삭제 행은 NULL이고 MySQL UNIQUE는 NULL을 서로 다른 값으로
-- 보므로, 삭제된 같은 날짜 행은 몇 개든 남고 활성 행은 하나뿐이다(boolean 컬럼이면 두 번째 삭제가 충돌한다).
-- 엔티티는 이 컬럼을 매핑하지 않는다 — 값은 MySQL이 계산하고 앱은 deleted_at만 쓴다.
ALTER TABLE daily_records
    ADD COLUMN active_marker TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL,
    ALGORITHM=INSTANT;

-- 새 unique를 추가한 뒤 옛 unique를 지운다(한 문장). 새 index도 subject_id로 시작해 fk_daily_records_subject에
-- 필요한 index와 (subject_id, record_date) 조회 prefix를 그대로 이어받는다.
ALTER TABLE daily_records
    ADD UNIQUE KEY uq_daily_records_subject_active_date (subject_id, record_date, active_marker),
    DROP INDEX uq_daily_records_subject_date,
    ALGORITHM=INPLACE, LOCK=NONE;
