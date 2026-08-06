-- 정리 후 잔여 0 검증. 모든 행의 residue_rows가 0이어야 완료다.
--
-- users를 이미 지웠으므로 user_id 집합으로는 확인할 수 없다. 대신 두 가지로 본다.
--   (a) 합성 사용자 자체가 남았는지 — provider_user_id 접두사
--   (b) 삭제된 사용자를 가리키는 고아 행이 남았는지 — 이쪽이 "정리가 끝났다"의 권위 있는 신호다.
--       cleanup이 일부만 실행돼도 반드시 여기서 잡힌다.

SELECT 'users' AS target_table, COUNT(*) AS residue_rows
FROM users
WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'

-- 부하 테스트가 만든 rawId 모양(`k6-<runId>-<code><step>-<5자리>-<2자리>`)만 정확히 집는다.
-- `k6-%`로 느슨하게 잡으면 사람이 손으로 넣은 `k6-`로 시작하는 실제 데이터까지 잔여로 오탐한다.
UNION ALL
SELECT 'timeline_draft_source_items(load-test shape)', COUNT(*)
FROM timeline_draft_source_items
WHERE raw_id LIKE 'k6-%-%-_____-__'

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
