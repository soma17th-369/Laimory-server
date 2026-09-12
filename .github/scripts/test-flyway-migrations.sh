#!/usr/bin/env bash
# Flyway 최초 편입 및 다중 프로세스 실행 계약. 기존 local DB·volume에는 연결하지 않는다.
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
  docker rm -f -v "$TEST_NAME-cli" "$TEST_NAME-first" "$TEST_NAME-second" "$TEST_NAME" >/dev/null 2>&1 || true
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
  docker run --rm --name "${FLYWAY_CONTAINER_NAME:-$TEST_NAME-cli}" --network "$TEST_NAME" \
    -e "FLYWAY_URL=jdbc:mysql://mysql:3306/$database?serverTimezone=Asia/Seoul&characterEncoding=UTF-8" \
    -e FLYWAY_USER=root -e FLYWAY_PASSWORD="$TEST_PASSWORD" \
    -v "$location:/flyway/sql:ro" \
    -v "$WORK/mysql-connector-j.jar:/flyway/drivers/mysql-connector-j.jar:ro" "$FLYWAY_IMAGE" \
    -driver=com.mysql.cj.jdbc.Driver -locations=filesystem:/flyway/sql \
    -baselineOnMigrate=false -cleanDisabled=true -outOfOrder=false -validateOnMigrate=true "$@"
}

# 별도 JVM/커넥션 두 개가 같은 DB/history를 사용한다. 양쪽 종료 상태를 모두 확인한다.
start_two_migrations() {
  phase=$1
  shift
  FLYWAY_CONTAINER_NAME="$TEST_NAME-first" flyway "$@" migrate >"$WORK/$phase-first.log" 2>&1 &
  first_pid=$!
  FLYWAY_CONTAINER_NAME="$TEST_NAME-second" flyway "$@" migrate >"$WORK/$phase-second.log" 2>&1 &
  second_pid=$!
}

wait_for_migrations() {
  first_status=0; wait "$first_pid" || first_status=$?
  second_status=0; wait "$second_pid" || second_status=$?
  [ "$first_status" -eq 0 ] && [ "$second_status" -eq 0 ] || fail 'concurrent migration process failed'
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
  # entrypoint의 --skip-networking 임시 서버는 제외하고 실제 TCP 서버를 기다린다.
  if mysql -h 127.0.0.1 --protocol=tcp -e 'SELECT 1' >/dev/null 2>&1; then ready=true; break; fi
  sleep 2
done
[ "$ready" = true ] || fail 'temporary MySQL did not become ready'
mysql -e 'CREATE DATABASE flyway_fresh; CREATE DATABASE flyway_legacy;'

# 최초 편입 회귀 검증은 V1에 고정한다. 이후 버전의 정상 추가가 이 검증을 깨뜨리면 안 된다.
start_two_migrations fresh flyway_fresh "$MIGRATIONS" -target=1
wait_for_migrations
[ "$(mysql flyway_fresh -e 'SELECT COUNT(*) FROM app_config')" = 1 ] || fail 'app_config seed missing or duplicated'
[ "$(mysql flyway_fresh -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME <> 'flyway_schema_history'")" = 17 ] || fail 'business table count changed'
[ "$(mysql flyway_fresh -e "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND type='SQL' AND success=1")" = 1 ] || fail 'V1 not recorded'
ok 'two processes on an empty DB create V1 and app_config exactly once'

dump flyway_fresh --no-create-info >"$WORK/fresh-before.sql"
flyway flyway_fresh "$MIGRATIONS" -target=1 migrate >"$WORK/repeat.log" 2>&1
dump flyway_fresh --no-create-info >"$WORK/fresh-after.sql"
cmp -s "$WORK/fresh-before.sql" "$WORK/fresh-after.sql" || fail 'repeat migrate changed data'
[ "$(mysql flyway_fresh -e 'SELECT COUNT(*) FROM flyway_schema_history')" = 1 ] || fail 'repeat migrate changed history'
ok 'repeat migrate preserves data and history'

# 도입 직전 저장소 SQL은 독립 fixture로 동결한다. 실제 DB의 #432 cutover 이름 네 개만
# 명시적으로 맞춘 사본을 편입한다. 이외 모든 DDL은 원문과 V1이 같아야 한다.
sed \
  -e 's/idx_term_agreements_user_history/idx_term_agreements_v2_432_history/g' \
  -e 's/idx_term_agreements_document/idx_term_agreements_v2_432_document/g' \
  -e 's/fk_term_agreements_document/fk_term_agreements_v2_432_document/g' \
  -e 's/chk_term_documents_version_canonical/chk_term_documents_v2_432_version/g' \
  "$LEGACY" >"$WORK/legacy-adoption.sql"
mysql flyway_legacy <"$WORK/legacy-adoption.sql"
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

if flyway flyway_legacy "$MIGRATIONS" -target=1 migrate >"$WORK/no-baseline.log" 2>&1; then
  fail 'non-empty DB was migrated without explicit baseline'
fi
grep -q 'Found non-empty schema' "$WORK/no-baseline.log" || fail 'migrate failed for an unrelated reason'
[ "$(mysql flyway_legacy -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='flyway_schema_history'")" = 0 ] || fail 'history created without baseline'
ok 'non-empty DB requires explicit baseline'

flyway flyway_legacy "$MIGRATIONS" -baselineVersion=1 baseline >"$WORK/baseline.log" 2>&1
flyway flyway_legacy "$MIGRATIONS" -target=1 migrate >"$WORK/adopt.log" 2>&1
flyway flyway_legacy "$MIGRATIONS" -target=1 validate >"$WORK/validate.log" 2>&1
dump flyway_legacy --no-create-info >"$WORK/legacy-after.sql"
cmp -s "$WORK/legacy-before.sql" "$WORK/legacy-after.sql" || fail 'baseline adoption changed existing data'
[ "$(mysql flyway_legacy -e "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND type='BASELINE' AND success=1")" = 1 ] || fail 'baseline not recorded'
[ "$(mysql flyway_legacy -e "SELECT COUNT(*) FROM flyway_schema_history WHERE type='SQL'")" = 0 ] || fail 'V1 was replayed on the existing DB'
ok 'baseline adoption preserves users, consent history and customized app_config'

mkdir "$WORK/tampered"
cp "$MIGRATIONS/"*.sql "$WORK/tampered/"
printf '\n-- changed after application\n' >>"$WORK/tampered/V1__initial_schema.sql"
if flyway flyway_fresh "$WORK/tampered" -target=1 validate >"$WORK/checksum.log" 2>&1; then
  fail 'modified V1 passed validation'
fi
grep -qi 'checksum mismatch' "$WORK/checksum.log" || fail 'validation failed for an unrelated reason'
ok 'modified applied migration fails checksum validation'

# 운영 migration에 추가하지 않는 임시 V2. 중복 실행하면 CREATE/INSERT가 실패한다.
mkdir "$WORK/concurrent"
cp "$MIGRATIONS/V1__initial_schema.sql" "$WORK/concurrent/"
cat >"$WORK/concurrent/V2__concurrent_probe.sql" <<'SQL'
SELECT GET_LOCK('flyway-migration-test-gate', 120);
CREATE TABLE migration_execution_probe (id INT NOT NULL PRIMARY KEY);
INSERT INTO migration_execution_probe (id) VALUES (1);
SELECT RELEASE_LOCK('flyway-migration-test-gate');
SQL

# 첫 migration을 gate에서 멈춰 두고 둘째 프로세스의 native Flyway 잠금 대기를 관측한다.
# 고정 sleep으로 동시 실행을 추측하지 않는다. gate 소유 연결만 끊어 migration을 진행시킨다.
mysql flyway_fresh -e "SELECT GET_LOCK('flyway-migration-test-gate', 0); SELECT SLEEP(120);" >"$WORK/gate.log" 2>&1 &
gate_pid=$!
for attempt in $(seq 1 30); do
  gate_connection=$(mysql -e "SELECT IS_USED_LOCK('flyway-migration-test-gate')")
  [ "$gate_connection" = NULL ] || break
  sleep 1
done
[ "$gate_connection" != NULL ] || fail 'test gate was not acquired'

start_two_migrations upgrade flyway_fresh "$WORK/concurrent" -target=2
lock_waiters=0
for attempt in $(seq 1 60); do
  lock_waiters=$(mysql -e "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB='flyway_fresh' AND INFO LIKE 'SELECT GET_LOCK(%'")
  [ "$lock_waiters" -lt 2 ] || break
  sleep 1
done
[ "$lock_waiters" -ge 2 ] || fail 'two migration processes did not overlap at the database locks'
mysql -e "KILL $gate_connection"
wait "$gate_pid" || true
wait_for_migrations

[ "$(mysql flyway_fresh -e 'SELECT COUNT(*) FROM migration_execution_probe WHERE id=1')" = 1 ] || fail 'V2 result missing or duplicated'
[ "$(mysql flyway_fresh -e "SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND type='SQL' AND success=1")" = 1 ] || fail 'V2 history missing or duplicated'
dump flyway_fresh --no-create-info --ignore-table=flyway_fresh.migration_execution_probe >"$WORK/concurrent-after.sql"
cmp -s "$WORK/fresh-before.sql" "$WORK/concurrent-after.sql" || fail 'concurrent migration changed existing data'
ok 'overlapping migrations wait for the native lock and apply V2 exactly once'

# #474: V1의 대표 초안 행을 보존하면서 선점 컬럼/인덱스만 제거한다.
# 위 잠금 시험의 임시 V2와 독립된 DB에서 실제 앱 migration을 검증한다.
mysql -e 'CREATE DATABASE flyway_scheduler_upgrade;'
flyway flyway_scheduler_upgrade "$MIGRATIONS" -target=1 migrate >"$WORK/scheduler-v1.log" 2>&1
mysql flyway_scheduler_upgrade <<'SQL'
INSERT INTO user_subject_links VALUES
  (UNHEX(REPEAT('1',64)), '00000000-0000-4000-8000-000000000001', 1);
INSERT INTO timeline_draft_source_items
  (timeline_draft_source_item_id, task_id, subject_id, item_type, raw_id, payload,
   created_at, updated_at, cleanup_available_at)
VALUES (1, '474-fixture', '00000000-0000-4000-8000-000000000001', 'CALENDAR',
        '00000000-0000-4000-8000-000000000002', JSON_OBJECT('title', 'fixture'),
        '2026-01-01 00:00:00', '2026-01-01 00:00:00', '2026-01-02 00:00:00');
SQL
mysql flyway_scheduler_upgrade -e 'SELECT timeline_draft_source_item_id, task_id, subject_id, item_type, raw_id, payload, created_at, updated_at, modified_by FROM timeline_draft_source_items' >"$WORK/scheduler-before.tsv"
flyway flyway_scheduler_upgrade "$MIGRATIONS" -target=2 migrate >"$WORK/scheduler-v2.log" 2>&1
mysql flyway_scheduler_upgrade -e 'SELECT timeline_draft_source_item_id, task_id, subject_id, item_type, raw_id, payload, created_at, updated_at, modified_by FROM timeline_draft_source_items' >"$WORK/scheduler-after.tsv"
cmp -s "$WORK/scheduler-before.tsv" "$WORK/scheduler-after.tsv" || fail 'V2 changed source data'
[ "$(mysql flyway_scheduler_upgrade -e "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_draft_source_items' AND COLUMN_NAME='cleanup_available_at'")" = 0 ] || fail 'cleanup column remains'
[ "$(mysql flyway_scheduler_upgrade -e "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_draft_source_items' AND INDEX_NAME='idx_draft_source_cleanup'")" = 0 ] || fail 'cleanup index remains'
[ "$(mysql flyway_scheduler_upgrade -e "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='timeline_draft_source_items' AND INDEX_NAME='idx_draft_source_created'")" = 1 ] || fail 'created_at index missing'
flyway flyway_scheduler_upgrade "$MIGRATIONS" -target=2 validate >"$WORK/scheduler-validate.log" 2>&1
ok 'V1 to V2 preserves source data and drops only the draft claim column and index'
