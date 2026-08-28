-- 테스트 데이터 정리. 05-cleanup-dry-run.sql 결과를 확인하고 manifest에 남긴 뒤에만 실행한다.
--
-- ⚠️ 반드시 subject-set.sql과 같은 세션에서 실행한다(임시 테이블은 세션 범위):
--   cat .artifacts/subject-set.sql sql/06-cleanup.sql | mysql --defaults-extra-file=... <db>
--
-- 삭제 순서가 중요하다:
--   1) timeline_items — 소유자 컬럼이 없어 junction으로만 사용자에 닿는다. daily_records를 먼저 지우면
--      junction이 cascade로 사라져 이 행들을 더 이상 식별할 수 없다(고아 잔여).
--   2) timeline_draft_source_items — subject_id로 직접 지운다.
--   3) daily_records — timeline_events와 timeline_event_items가 FK cascade로 함께 사라진다.
--   4) user_memories — subject FK가 RESTRICT라 mapping보다 먼저 지워야 한다.
--   5~7) subject 축 3종 — daily_notification_preferences → subject_preferences → user_subject_links.
--      전부 RESTRICT라 이 순서를 어기면 삭제가 거절된다.
--   8) users — 마지막.
--
-- 대상 경계는 합성 사용자 집합과 그 subject 집합뿐이다. 실제 Kakao provider_user_id는 숫자 문자열이라
-- 접두사가 겹치지 않고, subject 집합은 이 run이 심은 값만 담고 있다.
--
-- ⚠️ MySQL은 한 쿼리에서 TEMPORARY 테이블을 두 번 참조하지 못한다(ERROR 1137). 그래서 대상 집합을
--    단계별 임시 테이블로 나눠 각 문장이 각 임시 테이블을 정확히 한 번만 읽게 구성했다.
--    k6_251_subjects는 여기서 지우지 않는다 — 07-verify-residue.sql이 같은 세션에서 계속 쓴다.

DROP TEMPORARY TABLE IF EXISTS k6_251_records;
DROP TEMPORARY TABLE IF EXISTS k6_251_items;

CREATE TEMPORARY TABLE k6_251_records (
    daily_record_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

CREATE TEMPORARY TABLE k6_251_items (
    timeline_item_id BIGINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

-- 합성 subject의 daily_record 집합.
INSERT INTO k6_251_records (daily_record_id)
SELECT d.daily_record_id
FROM daily_records d
JOIN k6_251_subjects k ON k.subject_id = d.subject_id;

-- 삭제 대상 item: 모든 event 연결이 합성 record만 가리키는 item.
-- LEFT JOIN 뒤 "전체 연결 수 == 합성 record 연결 수"로 판정해 k6_251_records를 한 번만 읽는다.
-- 합성 밖 event에도 연결된 item은 자동으로 제외된다(실 데이터 보호).
INSERT INTO k6_251_items (timeline_item_id)
SELECT ei.timeline_item_id
FROM timeline_event_items ei
JOIN timeline_events e ON e.timeline_event_id = ei.timeline_event_id
LEFT JOIN k6_251_records r ON r.daily_record_id = e.daily_record_id
GROUP BY ei.timeline_item_id
HAVING COUNT(*) = SUM(r.daily_record_id IS NOT NULL);

START TRANSACTION;

-- 1) 합성 사용자에게만 연결된 timeline_items. AI 격리가 지켜졌다면 0행이다.
DELETE i FROM timeline_items i
JOIN k6_251_items k ON k.timeline_item_id = i.timeline_item_id;

-- 2) draft source rows.
DELETE s FROM timeline_draft_source_items s
JOIN k6_251_subjects k ON k.subject_id = s.subject_id;

-- 3) daily_records(+ timeline_events, timeline_event_items cascade).
DELETE FROM daily_records
WHERE daily_record_id IN (SELECT daily_record_id FROM k6_251_records);

-- 4) AI가 저장한 user memory(있다면).
DELETE m FROM user_memories m
JOIN k6_251_subjects k ON k.subject_id = m.subject_id;

-- 5~7) subject 축 3종. RESTRICT FK 사슬을 잎에서 뿌리 방향으로 지운다.
DELETE p FROM daily_notification_preferences p
JOIN k6_251_subjects k ON k.subject_id = p.subject_id;

DELETE p FROM subject_preferences p
JOIN k6_251_subjects k ON k.subject_id = p.subject_id;

DELETE l FROM user_subject_links l
JOIN k6_251_subjects k ON k.subject_id = l.subject_id;

-- 8) 합성 사용자.
DELETE FROM users
WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%';

COMMIT;

DROP TEMPORARY TABLE IF EXISTS k6_251_records;
DROP TEMPORARY TABLE IF EXISTS k6_251_items;
