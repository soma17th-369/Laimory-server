-- 정리 후 잔여 0 검증. 모든 residue_rows가 0이어야 완료다.
--
-- ⚠️ 반드시 subject-set.sql과 같은 세션에서 실행한다(임시 테이블은 세션 범위):
--   cat .artifacts/subject-set.sql sql/07-verify-residue.sql | mysql --defaults-extra-file=... <db>
--
-- 06이 users와 subject mapping을 이미 지웠으므로 "남아 있는 사용자"로는 확인할 수 없다.
-- 대신 정리와 무관하게 유지되는 두 좌표로 본다.
--   (a) 합성 사용자 자체가 남았는지 — provider_user_id 접두사
--   (b) 합성 subject를 가리키는 행이 남았는지 — k6_251_subjects. 콘텐츠 FK가 RESTRICT라 이 값이 0이 아니면
--       06의 어느 단계가 실패했다는 뜻이다(부분 실행도 반드시 여기서 잡힌다).
--
-- ⚠️ 한 문장이 임시 테이블을 두 번 참조하면 ERROR 1137이라 문장을 나눠 두었다(결과가 여러 그리드).

SELECT 'users' AS target_table, COUNT(*) AS residue_rows
FROM users
WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%';

-- 부하 테스트가 만든 rawId 모양(`k6-<runId>-<code><step>-<5자리>-<2자리>`)만 정확히 집는다.
-- `k6-%`로 느슨하게 잡으면 사람이 손으로 넣은 `k6-`로 시작하는 실제 데이터까지 잔여로 오탐한다.
SELECT 'timeline_draft_source_items(load-test shape)' AS target_table, COUNT(*) AS residue_rows
FROM timeline_draft_source_items
WHERE raw_id LIKE 'k6-%-%-_____-__';

SELECT 'timeline_draft_source_items (synthetic subject)' AS target_table, COUNT(*) AS residue_rows
FROM timeline_draft_source_items s
JOIN k6_251_subjects k ON k.subject_id = s.subject_id;

SELECT 'daily_records (synthetic subject)' AS target_table, COUNT(*) AS residue_rows
FROM daily_records d
JOIN k6_251_subjects k ON k.subject_id = d.subject_id;

SELECT 'user_memories (synthetic subject)' AS target_table, COUNT(*) AS residue_rows
FROM user_memories m
JOIN k6_251_subjects k ON k.subject_id = m.subject_id;

SELECT 'daily_notification_preferences (synthetic subject)' AS target_table, COUNT(*) AS residue_rows
FROM daily_notification_preferences p
JOIN k6_251_subjects k ON k.subject_id = p.subject_id;

SELECT 'subject_preferences (synthetic subject)' AS target_table, COUNT(*) AS residue_rows
FROM subject_preferences p
JOIN k6_251_subjects k ON k.subject_id = p.subject_id;

SELECT 'user_subject_links (synthetic subject)' AS target_table, COUNT(*) AS residue_rows
FROM user_subject_links l
JOIN k6_251_subjects k ON k.subject_id = l.subject_id;

-- 어떤 event에도 연결되지 않은 timeline_items — 이번 정리가 만든 고아가 없어야 한다.
-- (PHOTO 삭제 대기 job이 소유한 item은 정상 상태라 제외한다.)
SELECT 'timeline_items (orphan)' AS target_table, COUNT(*) AS residue_rows
FROM timeline_items i
WHERE NOT EXISTS (
    SELECT 1 FROM timeline_event_items ei WHERE ei.timeline_item_id = i.timeline_item_id
)
  AND NOT EXISTS (
    SELECT 1 FROM timeline_photo_delete_jobs j WHERE j.timeline_item_id = i.timeline_item_id
);
