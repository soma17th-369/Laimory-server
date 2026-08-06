-- 합성 사용자의 user_id를 VU 순서로 내보낸다. generate-tokens.py의 --user-ids 입력이 된다.
--
-- user_id는 AUTO_INCREMENT라 연속을 보장할 수 없으므로 범위가 아니라 실제 목록을 쓴다.
-- provider_user_id 순서가 VU 번호 순서다(VU n → 목록의 n번째 줄).
--
-- 실행(헤더·격자 없이 값만):
--   mysql --defaults-extra-file=... -N -B <db> < 02-export-user-ids.sql \
--     > load-tests/timeline-draft/.artifacts/user-ids.txt

SELECT user_id
FROM users
WHERE provider = 'KAKAO' AND provider_user_id LIKE 'k6-251-%'
ORDER BY provider_user_id;
