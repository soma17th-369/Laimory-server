#!/usr/bin/env bash
# Flyway 최초 생성/기존 DB 편입 계약. 기존 local DB·volume에는 연결하지 않는다.
# 실행: ./gradlew bootJar 후 bash .github/scripts/test-flyway-migrations.sh (Docker·unzip 필요)
set -euo pipefail
# 함수 호출의 로그 리다이렉션 중 실패해도 진단은 원래 stderr로 보낸다.
exec 3>&2

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MIGRATIONS="$REPO_ROOT/src/main/resources/db/migration"
LEGACY="$REPO_ROOT/src/test/resources/db/legacy/pre-flyway-schema.sql"
FLYWAY_IMAGE=flyway/flyway:11.7.2-alpine@sha256:a493a5ef0700f6d1ef4f7b83320f79601071b20749c2eca1d73dd2e352948656
WORK=$(mktemp -d)
TEST_NAME="laimory-flyway-$$-$RANDOM"
TEST_PASSWORD=flyway-local-test-only

cleanup() {
  result=$?
  if [ "$result" -ne 0 ]; then
    # 이 컨테이너에는 합성 fixture만 존재한다.
    for log in "$WORK"/*.log; do
      [ ! -f "$log" ] || tail -n 50 "$log" >&3
    done
  fi
  docker rm -f -v "$TEST_NAME" >/dev/null 2>&1 || true
  docker network rm "$TEST_NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK"
  exit "$result"
}
trap cleanup EXIT

fail() { echo "FAIL: $1" >&2; exit 1; }
ok() { echo "ok - $1"; }

# 공식 CLI는 MariaDB JDBC를 포함하므로 앱 JAR의 MySQL Connector/J를 그대로 사용한다.
# 인증 옵션을 느슨하게 만들거나 CLI 드라이버 버전을 별도로 관리하지 않는다.
set -- "$REPO_ROOT"/build/libs/*.jar
[ "$#" -eq 1 ] && [ -f "$1" ] || fail 'run ./gradlew bootJar first (expected one executable JAR)'
APP_JAR=$1
MYSQL_DRIVER=$(unzip -Z1 "$APP_JAR" | grep '^BOOT-INF/lib/mysql-connector-j-[^/]*\.jar$')
[ -n "$MYSQL_DRIVER" ] || fail 'MySQL Connector/J missing from app JAR'
unzip -p "$APP_JAR" "$MYSQL_DRIVER" >"$WORK/mysql-connector-j.jar"

mysql() {
  docker exec -i -e MYSQL_PWD="$TEST_PASSWORD" "$TEST_NAME" \
    mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names "$@"
}

flyway() {
  database=$1
  location=$2
  shift 2
  docker run --rm --network "$TEST_NAME" \
    -e "FLYWAY_URL=jdbc:mysql://mysql:3306/$database?serverTimezone=Asia/Seoul&characterEncoding=UTF-8" \
    -e FLYWAY_USER=root -e FLYWAY_PASSWORD="$TEST_PASSWORD" \
    -v "$location:/flyway/sql:ro" \
    -v "$WORK/mysql-connector-j.jar:/flyway/drivers/mysql-connector-j.jar:ro" "$FLYWAY_IMAGE" \
    -driver=com.mysql.cj.jdbc.Driver -locations=filesystem:/flyway/sql \
    -baselineOnMigrate=false -cleanDisabled=true -outOfOrder=false -validateOnMigrate=true "$@"
}

dump() {
  database=$1
  shift
  docker exec -e MYSQL_PWD="$TEST_PASSWORD" "$TEST_NAME" \
    mysqldump -uroot --default-character-set=utf8mb4 --skip-comments --no-tablespaces \
    --set-gtid-purged=OFF --skip-add-locks --skip-disable-keys --skip-extended-insert \
    --order-by-primary "--ignore-table=$database.flyway_schema_history" "$@" "$database"
}

docker network create "$TEST_NAME" >/dev/null
docker run -d --name "$TEST_NAME" --network "$TEST_NAME" --network-alias mysql \
  -e MYSQL_ROOT_PASSWORD="$TEST_PASSWORD" -e MYSQL_ROOT_HOST=% mysql:8.0 >/dev/null
ready=false
for attempt in $(seq 1 60); do
  if mysql -e 'SELECT 1' >/dev/null 2>&1; then ready=true; break; fi
  sleep 2
done
[ "$ready" = true ] || fail 'temporary MySQL did not become ready'
mysql -e 'CREATE DATABASE flyway_fresh; CREATE DATABASE flyway_legacy;'

flyway flyway_fresh "$MIGRATIONS" migrate >"$WORK/fresh.log" 2>&1
[ "$(mysql flyway_fresh -e 'SELECT COUNT(*) FROM app_config')" = 1 ] || fail 'app_config seed missing or duplicated'
[ "$(mysql flyway_fresh -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME <> 'flyway_schema_history'")" = 17 ] || fail 'business table count changed'
[ "$(mysql flyway_fresh -e "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND type='SQL' AND success=1")" = 1 ] || fail 'V1 not recorded'
ok 'empty DB creates V1 and the required app_config seed'

dump flyway_fresh --no-create-info >"$WORK/fresh-before.sql"
flyway flyway_fresh "$MIGRATIONS" migrate >"$WORK/repeat.log" 2>&1
dump flyway_fresh --no-create-info >"$WORK/fresh-after.sql"
cmp -s "$WORK/fresh-before.sql" "$WORK/fresh-after.sql" || fail 'repeat migrate changed data'
[ "$(mysql flyway_fresh -e 'SELECT COUNT(*) FROM flyway_schema_history')" = 1 ] || fail 'repeat migrate changed history'
ok 'repeat migrate preserves data and history'

# 운영 V1을 복사해 legacy를 만들지 않는다. 도입 직전 스키마를 독립 fixture로 고정한다.
mysql flyway_legacy <"$LEGACY"
dump flyway_fresh --no-data >"$WORK/fresh-schema.sql"
dump flyway_legacy --no-data >"$WORK/legacy-schema.sql"
diff -u "$WORK/legacy-schema.sql" "$WORK/fresh-schema.sql" || fail 'V1 changed the pre-Flyway schema'
ok 'V1 preserves columns, defaults, collations, indexes, FK and CHECK definitions'

mysql flyway_legacy <<'SQL'
UPDATE app_config SET min_app_version=7, recommend_app_version=9, debug_test_message='기존 설정 보존';
INSERT INTO users (user_id, provider, provider_user_id, nickname, created_at, updated_at)
VALUES (42, 'GOOGLE', 'flyway-test-user', '기존 회원', '2026-01-02 03:04:05.123456', '2026-01-02 03:04:05.123456');
INSERT INTO term_documents (term_type, version, title, content_url, created_at, updated_at)
VALUES ('TERMS_OF_SERVICE', '1.0', '테스트 약관', 'https://example.invalid/terms/1.0', '2026-01-02 03:04:05.123456', '2026-01-02 03:04:05.123456');
INSERT INTO term_agreements (user_id, term_type, version, accepted_at, created_at, updated_at)
VALUES (42, 'TERMS_OF_SERVICE', '1.0', '2026-01-02 03:04:05.123456', '2026-01-02 03:04:05.123456', '2026-01-02 03:04:05.123456');
SQL
dump flyway_legacy --no-create-info >"$WORK/legacy-before.sql"

if flyway flyway_legacy "$MIGRATIONS" migrate >"$WORK/no-baseline.log" 2>&1; then
  fail 'non-empty DB was migrated without explicit baseline'
fi
grep -q 'Found non-empty schema' "$WORK/no-baseline.log" || fail 'migrate failed for an unrelated reason'
[ "$(mysql flyway_legacy -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='flyway_schema_history'")" = 0 ] || fail 'history created without baseline'
ok 'non-empty DB requires explicit baseline'

flyway flyway_legacy "$MIGRATIONS" -baselineVersion=1 baseline >"$WORK/baseline.log" 2>&1
flyway flyway_legacy "$MIGRATIONS" migrate >"$WORK/adopt.log" 2>&1
flyway flyway_legacy "$MIGRATIONS" validate >"$WORK/validate.log" 2>&1
dump flyway_legacy --no-create-info >"$WORK/legacy-after.sql"
cmp -s "$WORK/legacy-before.sql" "$WORK/legacy-after.sql" || fail 'baseline adoption changed existing data'
[ "$(mysql flyway_legacy -e "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND type='BASELINE' AND success=1")" = 1 ] || fail 'baseline not recorded'
[ "$(mysql flyway_legacy -e "SELECT COUNT(*) FROM flyway_schema_history WHERE type='SQL'")" = 0 ] || fail 'V1 was replayed on the existing DB'
ok 'baseline adoption preserves users, consent history and customized app_config'

mkdir "$WORK/tampered"
cp "$MIGRATIONS/"*.sql "$WORK/tampered/"
printf '\n-- changed after application\n' >>"$WORK/tampered/V1__initial_schema.sql"
if flyway flyway_fresh "$WORK/tampered" validate >"$WORK/checksum.log" 2>&1; then
  fail 'modified V1 passed validation'
fi
grep -qi 'checksum mismatch' "$WORK/checksum.log" || fail 'validation failed for an unrelated reason'
ok 'modified applied migration fails checksum validation'
