-- run 한 단계의 결과 검증. "서로 다른 사용자 N명이 각각 정확히 한 번 접수됐다"를 DB로 증명한다.
--
-- rawId 규칙은 payload.js가 만드는 `k6-<runId>-<scenarioCode><stepIndex>-<vu>-<index>`다.
-- task_id는 서버가 만든 UUIDv7이라 run 식별은 rawId 접두사로 한다. scenarioCode는
-- calendar-core=c, geo-1-stay=g1, geo-18-stay=g18이고 stepIndex는 사다리 단계 번호(0부터)다.
--
-- 사용: @run_id / @scenario_step을 실행한 값으로 바꾼 뒤 실행한다.
--   예) RUN_ID=20260806-01의 geo-1-stay 4번째 단계  →  @run_id='20260806-01', @scenario_step='g13'
--   한 시나리오의 모든 단계를 한 번에 보려면 @scenario_step='g1%' 처럼 와일드카드를 쓴다.

SET @run_id = 'REPLACE_WITH_RUN_ID';
SET @scenario_step = 'REPLACE_WITH_SCENARIO_STEP';
SET @prefix = CONCAT('k6-', @run_id, '-', @scenario_step, '-');

-- 1) 접수 총량. calendar-core와 geo-1-stay는 tasks = distinct_users = source_rows = VUS여야 하고,
--    geo-18-stay는 source_rows = VUS * 18이다.
SELECT
    COUNT(*)                    AS source_rows,
    COUNT(DISTINCT task_id)     AS tasks,
    COUNT(DISTINCT user_id)     AS distinct_users
FROM timeline_draft_source_items
WHERE raw_id LIKE CONCAT(@prefix, '%');

-- 2) 사용자당 task 수 분포 — 한 사용자가 두 번 접수되지 않았는지. 기대: tasks_per_user = 1 행만 존재.
SELECT tasks_per_user, COUNT(*) AS users
FROM (
    SELECT user_id, COUNT(DISTINCT task_id) AS tasks_per_user
    FROM timeline_draft_source_items
    WHERE raw_id LIKE CONCAT(@prefix, '%')
    GROUP BY user_id
) per_user
GROUP BY tasks_per_user
ORDER BY tasks_per_user;

-- 3) task당 item 수 분포 — 시나리오별 기대값(1 또는 18)과 일치해야 한다.
SELECT items_per_task, COUNT(*) AS tasks
FROM (
    SELECT task_id, COUNT(*) AS items_per_task
    FROM timeline_draft_source_items
    WHERE raw_id LIKE CONCAT(@prefix, '%')
    GROUP BY task_id
) per_task
GROUP BY items_per_task
ORDER BY items_per_task;

-- 4) 지오코딩 enrich 결과 — geo 시나리오에서만 의미가 있다. simulator가 붙었다면 address가 채워지고
--    places 배열이 비어 있지 않다. calendar-core는 stay_items가 0이다.
SELECT
    COUNT(*)                                                AS stay_items,
    SUM(JSON_EXTRACT(payload, '$.address') IS NOT NULL)     AS with_address,
    SUM(JSON_LENGTH(JSON_EXTRACT(payload, '$.places')) > 0) AS with_places
FROM timeline_draft_source_items
WHERE raw_id LIKE CONCAT(@prefix, '%')
  AND item_type = 'STAY';

-- 5) 접수 시각 창 — 첫 행과 마지막 행의 간격(서버 측 관점). k6의 request start window와 대조한다.
SELECT
    MIN(created_at)                                                     AS first_row_at,
    MAX(created_at)                                                     AS last_row_at,
    TIMESTAMPDIFF(MICROSECOND, MIN(created_at), MAX(created_at)) / 1000 AS spread_ms
FROM timeline_draft_source_items
WHERE raw_id LIKE CONCAT(@prefix, '%');
