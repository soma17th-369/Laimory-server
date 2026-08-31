-- run 한 단계의 결과 검증. "서로 다른 사용자 N명이 각각 정확히 한 번 접수됐다"를 DB로 증명한다.
--
-- 소유자 컬럼은 `subject_id`다 — 콘텐츠 테이블은 raw user_id를 저장하지 않는다(가명화 설계).
-- 합성 사용자 1명 = subject 1개이므로 "서로 다른 subject 수"가 곧 "서로 다른 사용자 수"다.
--
-- rawId 규칙은 payload.js가 만드는 `k6-<runId>-<scenarioCode><stepIndex>-<vu>-<index>`다.
-- task_id는 서버가 만든 UUIDv7이라 run 식별은 rawId 접두사로 한다. scenarioCode는
-- calendar-core=c, mixed-day=m, geo-day=gd이고 stepIndex는 사다리 단계 번호(0부터)다.
--
-- rawId는 canonical UUID다(서버가 그 외 값을 400으로 거절한다 — RawIds). 식별 정보는 자릿수 안에
-- hex로 인코딩돼 있다: <YYYYMMDD>-<seq4>-<scenario+step>-<vu4>-<index12>
--
-- @scenario_step은 그 세 번째 그룹(4 hex)이다 — 시나리오 한 자리 + 단계 3자리.
--   시나리오: calendar-core=1, mixed-day=2, geo-day=3   (k6/lib/config.js의 SCENARIO_DIGITS)
--   단계: STEP_INDEX를 hex 3자리로 (0→000, 1→001, 10→00a)
--
-- 사용: @run_id / @scenario_step을 실행한 값으로 바꾼 뒤 실행한다.
--   예) RUN_ID=20260806-01의 geo-day 4번째 단계(STEP_INDEX=3) → @run_id='20260806-01', @scenario_step='3003'
--   한 시나리오의 모든 단계를 한 번에 보려면 @scenario_step='3%' 처럼 와일드카드를 쓴다.

SET @run_id = 'REPLACE_WITH_RUN_ID';
SET @scenario_step = 'REPLACE_WITH_SCENARIO_STEP';
-- @run_id는 사람이 쓰는 형태(YYYYMMDD-NN)를 그대로 받고 여기서 rawId 자릿수로 맞춘다(NN → 4자리).
SET @prefix = CONCAT(
    SUBSTRING_INDEX(@run_id, '-', 1), '-',
    LPAD(SUBSTRING_INDEX(@run_id, '-', -1), 4, '0'), '-',
    @scenario_step, '-');

-- 1) 접수 총량. tasks = distinct_subjects = VUS여야 하고, source_rows는 시나리오의 요청당 item 수를
--    곱한 값이다(calendar-core 1, mixed-day·geo-day 68).
SELECT
    COUNT(*)                    AS source_rows,
    COUNT(DISTINCT task_id)     AS tasks,
    COUNT(DISTINCT subject_id)  AS distinct_subjects
FROM timeline_draft_source_items
WHERE raw_id LIKE CONCAT(@prefix, '%');

-- 2) 사용자당 task 수 분포 — 한 사용자가 두 번 접수되지 않았는지. 기대: tasks_per_subject = 1 행만 존재.
SELECT tasks_per_subject, COUNT(*) AS subjects
FROM (
    SELECT subject_id, COUNT(DISTINCT task_id) AS tasks_per_subject
    FROM timeline_draft_source_items
    WHERE raw_id LIKE CONCAT(@prefix, '%')
    GROUP BY subject_id
) per_subject
GROUP BY tasks_per_subject
ORDER BY tasks_per_subject;

-- 3) task당 item 수 분포 — 시나리오별 기대값(1 또는 68)과 일치해야 한다.
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
