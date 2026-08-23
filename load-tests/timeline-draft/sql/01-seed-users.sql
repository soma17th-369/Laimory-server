-- #251 합성 부하 테스트 사용자 생성. 재실행해도 안전하다(이미 있는 행은 건너뛴다).
--
-- 식별 규칙: provider='KAKAO' AND provider_user_id LIKE 'k6-251-%'.
-- 실제 Kakao provider_user_id(OIDC sub)는 숫자 문자열이라 이 접두사와 절대 겹치지 않는다 —
-- 정리 절차가 실제 사용자를 건드릴 수 없다는 근거가 이것이다.
--
-- 감사 컬럼은 JPA auditing을 거치지 않으므로 직접 채운다. dev-mysql 호스트는 UTC이고 애플리케이션은
-- Asia/Seoul 벽시계로 DATETIME을 저장하므로 NOW()를 그대로 쓰면 앱 기준 9시간 과거가 된다.
-- UTC_TIMESTAMP()는 세션 타임존과 무관하게 항상 UTC라 변환 기준으로 안전하다.
--
-- 실행:
--   mysql --defaults-extra-file=... <db> < 01-seed-users.sql

SET SESSION cte_max_recursion_depth = 5000;
SET @user_count = 1000;

INSERT INTO users (provider, provider_user_id, email, nickname, created_at, updated_at, modified_by)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @user_count
)
SELECT
    'KAKAO',
    CONCAT('k6-251-', LPAD(seq.n, 6, '0')),
    NULL,                                                   -- email은 심지 않는다(개인정보 비복제)
    CONCAT('k6-251-user-', LPAD(seq.n, 6, '0')),
    CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'),
    CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00'),
    'k6-251'
FROM seq
WHERE NOT EXISTS (
    SELECT 1 FROM users u
    WHERE u.provider = 'KAKAO'
      AND u.provider_user_id = CONCAT('k6-251-', LPAD(seq.n, 6, '0'))
);

-- 확인: 기대 행 수와 user_id 범위. created_at이 Seoul 벽시계인지 seoul_now와 나란히 본다.
SELECT
    COUNT(*)                                          AS seeded_users,
    MIN(user_id)                                      AS min_user_id,
    MAX(user_id)                                      AS max_user_id,
    MIN(created_at)                                   AS min_created_at,
    CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00')  AS seoul_now
FROM users
WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%';
