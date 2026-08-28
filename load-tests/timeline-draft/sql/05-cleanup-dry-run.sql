-- 정리 dry-run — 실제 삭제 없이 06-cleanup.sql이 지울 행 수를 미리 센다.
-- 06을 실행하기 전에 반드시 이 결과를 manifest에 남긴다.
--
-- 대상 경계는 두 가지다.
--   (a) 합성 사용자: provider='KAKAO' AND provider_user_id LIKE 'k6-251-%'
--   (b) 합성 subject: 임시 테이블 k6_251_subjects (.artifacts/subject-set.sql이 만든다)
-- 콘텐츠 테이블은 raw user_id를 저장하지 않으므로 (a)로는 닿을 수 없다 — 그래서 (b)를 밖에서 넣는다.
--
-- ⚠️ 반드시 subject-set.sql과 같은 세션에서 실행한다(임시 테이블은 세션 범위):
--   cat load-tests/timeline-draft/.artifacts/subject-set.sql \
--       load-tests/timeline-draft/sql/05-cleanup-dry-run.sql | mysql --defaults-extra-file=... <db>
--
-- ⚠️ 한 문장이 임시 테이블을 두 번 참조하면 MySQL이 ERROR 1137로 거절한다. 그래서 UNION ALL 한 덩어리가
--    아니라 문장을 나눠 두었다 — 결과가 여러 그리드로 나온다.

SELECT 'users' AS target_table, COUNT(*) AS rows_to_delete
FROM users
WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%';

SELECT 'timeline_draft_source_items' AS target_table, COUNT(*) AS rows_to_delete
FROM timeline_draft_source_items s
JOIN k6_251_subjects k ON k.subject_id = s.subject_id;

SELECT 'daily_records' AS target_table, COUNT(*) AS rows_to_delete
FROM daily_records d
JOIN k6_251_subjects k ON k.subject_id = d.subject_id;

-- daily_records 삭제 시 FK ON DELETE CASCADE로 함께 사라지는 행(직접 삭제하지 않는다).
SELECT 'timeline_events (cascade)' AS target_table, COUNT(*) AS rows_to_delete
FROM timeline_events e
JOIN daily_records d ON d.daily_record_id = e.daily_record_id
JOIN k6_251_subjects k ON k.subject_id = d.subject_id;

-- timeline_items에는 소유자 컬럼이 없다 — event junction으로만 사용자에 닿는다. daily_records를 먼저
-- 지우면 junction만 cascade로 사라지고 item 행이 고아로 남으므로 06이 이 집합을 먼저 지운다.
-- AI가 결과를 저장하지 않았다면(noop, 또는 FAILED 콜백만 오는 simulator) 이 값은 0이어야 한다.
-- 0이 아니면 격리 전제가 깨진 것이므로 멈추고 원인을 먼저 확인한다.
--
-- LEFT JOIN 뒤 "전체 연결 수 == 합성 record 연결 수"로 판정해 임시 테이블을 한 번만 읽는다.
-- 합성 밖 event에도 연결된 item은 자동으로 제외된다(실 데이터 보호).
SELECT 'timeline_items (orphan-to-be)' AS target_table, COUNT(*) AS rows_to_delete
FROM (
    SELECT ei.timeline_item_id
    FROM timeline_event_items ei
    JOIN timeline_events e ON e.timeline_event_id = ei.timeline_event_id
    LEFT JOIN (
        SELECT d.daily_record_id
        FROM daily_records d
        JOIN k6_251_subjects k ON k.subject_id = d.subject_id
    ) r ON r.daily_record_id = e.daily_record_id
    GROUP BY ei.timeline_item_id
    HAVING COUNT(*) = SUM(r.daily_record_id IS NOT NULL)
) synthetic_items;

-- subject 축 행(가입 transaction이 만드는 3종). FK가 RESTRICT라 이 순서를 지키지 않으면 삭제가 막힌다:
-- daily_notification_preferences → subject_preferences → user_subject_links.
SELECT 'daily_notification_preferences' AS target_table, COUNT(*) AS rows_to_delete
FROM daily_notification_preferences p
JOIN k6_251_subjects k ON k.subject_id = p.subject_id;

SELECT 'subject_preferences' AS target_table, COUNT(*) AS rows_to_delete
FROM subject_preferences p
JOIN k6_251_subjects k ON k.subject_id = p.subject_id;

SELECT 'user_subject_links' AS target_table, COUNT(*) AS rows_to_delete
FROM user_subject_links l
JOIN k6_251_subjects k ON k.subject_id = l.subject_id;

-- AI가 user memory를 저장했다면 남는다(simulator의 user-memory webhook은 저장까지 한다).
-- subject FK가 RESTRICT라 이 행이 남아 있으면 user_subject_links 삭제가 막힌다.
SELECT 'user_memories' AS target_table, COUNT(*) AS rows_to_delete
FROM user_memories m
JOIN k6_251_subjects k ON k.subject_id = m.subject_id;

-- 아래 넷은 0이어야 한다. 부하 테스트는 로그인 흐름·푸시 등록·약관 동의·탈퇴를 태우지 않기 때문이다.
-- refresh_tokens·push_registrations는 06이 지우지 않는다(0이 아니면 전제가 깨진 것이라 사람이 확인한다).
-- term_agreements·account_erasure_jobs는 users FK가 RESTRICT라 남아 있으면 users 삭제가 실패한다.
SELECT 'refresh_tokens (expected 0)' AS target_table, COUNT(*) AS rows_to_delete
FROM refresh_tokens
WHERE user_id IN (
    SELECT user_id FROM users WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'
);

-- push_registrations의 소유자 컬럼은 subject_id다(FK 없는 soft owner).
SELECT 'push_registrations (expected 0)' AS target_table, COUNT(*) AS rows_to_delete
FROM push_registrations r
JOIN k6_251_subjects k ON k.subject_id = r.subject_id;

SELECT 'term_agreements (expected 0)' AS target_table, COUNT(*) AS rows_to_delete
FROM term_agreements
WHERE user_id IN (
    SELECT user_id FROM users WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'
);

SELECT 'account_erasure_jobs (expected 0)' AS target_table, COUNT(*) AS rows_to_delete
FROM account_erasure_jobs
WHERE user_id IN (
    SELECT user_id FROM users WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'
);
