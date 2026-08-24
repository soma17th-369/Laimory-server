-- 정리 dry-run — 실제 삭제 없이 06-cleanup.sql이 지울 행 수를 미리 센다.
-- 06을 실행하기 전에 반드시 이 결과를 manifest에 남긴다.
--
-- 대상 경계는 합성 사용자 집합 하나뿐이다: provider='KAKAO' AND provider_user_id LIKE 'k6-251-%'.

SELECT 'users' AS target_table, COUNT(*) AS rows_to_delete
FROM users
WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'

UNION ALL
SELECT 'timeline_draft_source_items', COUNT(*)
FROM timeline_draft_source_items
WHERE user_id IN (
    SELECT user_id FROM users WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'
)

UNION ALL
SELECT 'daily_records', COUNT(*)
FROM daily_records
WHERE user_id IN (
    SELECT user_id FROM users WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'
)

-- daily_records 삭제 시 FK ON DELETE CASCADE로 함께 사라지는 행(직접 삭제하지 않는다).
UNION ALL
SELECT 'timeline_events (cascade)', COUNT(*)
FROM timeline_events e
WHERE e.daily_record_id IN (
    SELECT daily_record_id FROM daily_records
    WHERE user_id IN (
        SELECT user_id FROM users WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'
    )
)

-- timeline_items에는 user_id가 없다 — event junction으로만 사용자에 닿는다. daily_records를 먼저 지우면
-- junction만 cascade로 사라지고 item 행이 고아로 남으므로 06이 이 집합을 먼저 지운다.
-- AI가 noop이면 결과 저장이 없어 이 값은 0이어야 한다. 0이 아니면 격리 전제가 깨진 것이므로 멈춘다.
UNION ALL
SELECT 'timeline_items (orphan-to-be)', COUNT(*)
FROM timeline_items i
WHERE i.timeline_item_id IN (
    SELECT ei.timeline_item_id
    FROM timeline_event_items ei
    JOIN timeline_events e ON e.timeline_event_id = ei.timeline_event_id
    JOIN daily_records d ON d.daily_record_id = e.daily_record_id
    WHERE d.user_id IN (
        SELECT user_id FROM users WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'
    )
)
  -- 합성 사용자 밖의 event에도 연결된 item은 제외한다(실 데이터 보호).
  AND NOT EXISTS (
      SELECT 1
      FROM timeline_event_items ei2
      JOIN timeline_events e2 ON e2.timeline_event_id = ei2.timeline_event_id
      JOIN daily_records d2 ON d2.daily_record_id = e2.daily_record_id
      WHERE ei2.timeline_item_id = i.timeline_item_id
        AND d2.user_id NOT IN (
            SELECT user_id FROM users WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'
        )
  )

-- 이 두 값은 0이어야 한다. 부하 테스트는 로그인 흐름과 푸시 등록을 태우지 않기 때문이다.
UNION ALL
SELECT 'refresh_tokens (expected 0)', COUNT(*)
FROM refresh_tokens
WHERE user_id IN (
    SELECT user_id FROM users WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'
)

UNION ALL
SELECT 'push_registrations (expected 0)', COUNT(*)
FROM push_registrations
WHERE user_id IN (
    SELECT user_id FROM users WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'
);
