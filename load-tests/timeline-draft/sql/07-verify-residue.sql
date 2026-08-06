-- 정리 후 잔여 0 검증. 모든 행의 residue_rows가 0이어야 완료다.
--
-- users를 이미 지웠으므로 user_id 집합으로는 확인할 수 없다. 대신 run이 남기는 고유 문자열
-- (provider_user_id 접두사, rawId 접두사)로 직접 훑는다.

SELECT 'users' AS target_table, COUNT(*) AS residue_rows
FROM users
WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'

UNION ALL
SELECT 'timeline_draft_source_items', COUNT(*)
FROM timeline_draft_source_items
WHERE raw_id LIKE 'k6-%'

-- 고아 확인: 삭제된 사용자를 가리키는 행이 남았는지(FK가 없는 컬럼이라 직접 본다).
UNION ALL
SELECT 'daily_records (orphan user)', COUNT(*)
FROM daily_records d
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.user_id = d.user_id)

UNION ALL
SELECT 'timeline_draft_source_items (orphan user)', COUNT(*)
FROM timeline_draft_source_items s
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.user_id = s.user_id)

-- 어떤 event에도 연결되지 않은 timeline_items — 이번 정리가 만든 고아가 없어야 한다.
-- (PHOTO 삭제 대기 job이 소유한 item은 정상 상태라 제외한다.)
UNION ALL
SELECT 'timeline_items (orphan)', COUNT(*)
FROM timeline_items i
WHERE NOT EXISTS (
    SELECT 1 FROM timeline_event_items ei WHERE ei.timeline_item_id = i.timeline_item_id
)
  AND NOT EXISTS (
    SELECT 1 FROM timeline_photo_delete_jobs j WHERE j.timeline_item_id = i.timeline_item_id
);
