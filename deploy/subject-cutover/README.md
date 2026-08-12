# Laimory dev subject cutover runbook (#285)

subject schema·PHOTO namespace 전환(#280 Epic)의 **forward 전용** quiescent cutover 절차다.
2026-08-12 결정으로 rollback을 지원하지 않는다 — 모든 실패는 forward-fix(수정 커밋 → build-only 재빌드
→ 같은 단계부터 재개)로만 대응하고, 검증 통과 후 legacy는 별도 승인 하에 즉시 삭제한다.

실제 AWS·host·DB 상태가 권위 원천이다. 모든 live 변경(DDL, Redis 삭제, S3 삭제, 컨테이너 조작)은
대상·영향·검증·복구 명령을 사전 검토하고 **사용자의 명시적 승인을 받은 뒤** 실행한다.
명령 출력·기록에 raw userId·HMAC·subject·URL·JSON 값과 secret을 남기지 않는다 — **건수만 기록한다.**

## 관련 계약

| 자산 | 계약 |
|---|---|
| `.github/workflows/deploy.yml` | `dev` push 자동 배포. repo variable `DEPLOY_PAUSED=true`면 push 배포 skip(컨테이너·`.env` 불변). `workflow_dispatch` input `image_sha`+`image_digest`가 deploy-existing(빌드 없이 기록한 exact ECR image를 digest로 배포, pause 무시) |
| `.github/workflows/build-only.yml` | `workflow_dispatch` input `image_sha` — exact SHA checkout 검증 후 docker build + ECR push만(배포 없음), summary에 SHA tag·digest 기록 |
| `app.subject.migration.mode` | `backfill-mappings` / `backfill-owners` / `verify-owners` — one-shot 도구(#285, `user/migration/`) |
| `app.photo.migration.mode` | `copy-verify` / `rewrite-urls` / `delete-legacy` — one-shot 도구(#284/#285, `timeline/photo/migration/`). subject 모드와 동시 설정은 기동 실패(상호 배타) |
| dev WAS | 컨테이너 이름 `laimory`, env는 `/home/ubuntu/app/.env` 단일 권위, ECR repo `laimory`(ap-northeast-2) |
| 배치 | draft cleanup 04:00·User Memory 04:30(JVM TZ), photo delete 03:00 Asia/Seoul. draft/UM task·guard TTL 3분 |

migration 모드는 **반드시 한 번에 하나만** 실행한다(도구가 동시 설정을 기동 실패로 차단하지만,
순차 실행 자체가 runbook 계약이다). 각 실행은 exit code 0과 건수 로그를 확인한 뒤 다음으로 넘어간다.

## 0) 사전조건

아래 count만 사전에 확인한다. UUID column DDL은 현재 배포 image가 `BINARY(16)`을 사용하므로 서비스가
살아 있는 동안 실행하지 않고, 3단계에서 구 컨테이너를 중지한 뒤 3.1단계에서 적용한다.

```sql
-- 두 User Memory 테이블은 공개 전 빈 상태라는 결정의 검증. 하나라도 0이 아니면 중단한다.
SELECT COUNT(*) FROM user_memories;          -- 0
SELECT COUNT(*) FROM user_memory_documents; -- 0

```

- [ ] dev `.env`의 AI 모드 확인 — `fake`면 04:30 User Memory 배치가 `user_memories`에 실제 write할 수
  있으므로 window 산정에 반영한다(값 자체는 secret이 아니다).

```bash
# dev WAS SSM 세션
grep -q '^APP_AI_MODE=fake$' /home/ubuntu/app/.env && echo "AI fake 모드 - 04:30 배치 write 가능"
```

- [ ] JVM TZ 확인 — 04:00/04:30 cron은 zone 미지정이라 JVM TZ 벽시계로 해석된다(window 산정 기준).

```bash
sudo docker exec laimory sh -c 'date; echo "TZ=$TZ"'
```

- [ ] pending photo delete job 0건 확인(0이 아니면 cutover 중단 — pending object migration 정책을
  임의로 만들지 않는다. `copy-verify` 도구도 같은 조건을 preflight로 재확인한다).

```sql
SELECT COUNT(*) FROM timeline_photo_delete_jobs;  -- 0이어야 한다
```

**실패 시**: 두 User Memory 테이블 중 하나라도 0행이 아니면 direct 전환 결정을 적용할 수 없으므로
중단한다. photo delete job이 있으면 03:00 배치 실행 후 0건을 재확인하고 진행한다.

## 1) 자동 배포 pause

```bash
gh variable set DEPLOY_PAUSED --repo soma17th-369/Laimory-server --body true
gh variable get DEPLOY_PAUSED --repo soma17th-369/Laimory-server   # true 확인
```

pause는 이미 gate를 통과한 run에 소급 적용되지 않는다. pause 설정 전에 시작한 running/queued run을
전부 기다린 뒤 active run 0건을 확인한다.

```bash
ACTIVE_RUNS=$(gh run list --repo soma17th-369/Laimory-server --workflow deploy.yml --limit 100 \
  --json databaseId,status \
  --jq '.[] | select(.status == "in_progress" or .status == "queued" or .status == "requested" or .status == "waiting" or .status == "pending") | .databaseId')
for RUN_ID in $ACTIVE_RUNS; do
  gh run watch "$RUN_ID" --repo soma17th-369/Laimory-server --compact
done
test "$(gh run list --repo soma17th-369/Laimory-server --workflow deploy.yml --limit 100 \
  --json status \
  --jq '[.[] | select(.status == "in_progress" or .status == "queued" or .status == "requested" or .status == "waiting" or .status == "pending")] | length')" -eq 0
```

dev WAS에서 `.env`와 실행 중 컨테이너의 SHA가 같음을 확인하고 그 40자 SHA를 기록한다(비밀 아님).
3단계에서 stop 직전에 같은 값인지 다시 확인한다.

```bash
# dev WAS SSM 세션
ENV_SHA=$(sed -n 's/^APP_COMMIT_SHA=//p' /home/ubuntu/app/.env)
CONTAINER_SHA=$(sudo docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' laimory \
  | sed -n 's/^APP_COMMIT_SHA=//p')
test "$(printf '%s' "$ENV_SHA" | wc -l)" -eq 0
printf '%s' "$ENV_SHA" | grep -Eq '^[0-9a-f]{40}$'
test "$ENV_SHA" = "$CONTAINER_SHA"
printf 'stable old image SHA: %s\n' "$ENV_SHA"
```

이후 새 push 배포는 workflow 레벨에서 skip되어 실행 중 컨테이너와 `.env`가 불변이다. pause를
무시하는 manual `workflow_dispatch`는 cutover의 9단계 전까지 실행하지 않는다.

## 2) #283 merge와 build-only

1. #283 final activation PR을 `dev`에 merge한다. push workflow가 pause로 skip되는지(기존 컨테이너
   계속 실행) Actions에서 확인한다.
2. merge된 exact `dev` SHA를 먼저 고정한 뒤 그 SHA를 build-only로 ECR에 push하고
   workflow summary의 image tag·digest를 기록한다.

```bash
SHA=$(gh api repos/soma17th-369/Laimory-server/commits/dev --jq .sha)
gh workflow run build-only.yml --repo soma17th-369/Laimory-server -f image_sha="$SHA"
# 완료 후 run summary의 SHA·digest를 기록한다.
DIGEST=<summary에 기록된 sha256:64-hex>
aws ecr describe-images --repository-name laimory --region ap-northeast-2 \
  --image-ids imageTag="$SHA" --query 'imageDetails[0].imageDigest' --output text | grep -qxF "$DIGEST"
```

**실패 시**: build 실패는 forward-fix commit 후 build-only 재실행(새 SHA로 기록 갱신).

## 3) maintenance window 진입과 quiesce

- 03:00(photo delete, Asia/Seoul)·04:00(draft cleanup)·04:30(User Memory) 배치를 피한 시간대를
  잡는다(0단계에서 확인한 JVM TZ 기준).
- 구 컨테이너를 중지한다(이후 새 dispatch·AI 결과·Event/photo write 없음).

```bash
# dev WAS SSM 세션
EXPECTED_OLD_SHA=<1단계에서 기록한 40자 SHA>
grep -qxF "APP_COMMIT_SHA=$EXPECTED_OLD_SHA" /home/ubuntu/app/.env
sudo docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' laimory \
  | grep -qxF "APP_COMMIT_SHA=$EXPECTED_OLD_SHA"
sudo docker stop laimory && sudo docker rm laimory
```

- 마지막 dispatch 후 **3분**(draft·User Memory task/guard TTL)이 지나 old result가 더는 수용되지
  않음을 확인한다. 중지 시점에 in-flight AI 콜백이 있었다면 연결 실패로 유실된다 — pre-release
  데이터라 수용하고 forward로만 진행한다(계획 §5.4).

```bash
# 값 비출력 — 건수만 기록 (redis-cli 접속 값은 dev WAS .env의 REDIS_* 계약이 권위)
redis-cli -h <REDIS_HOST> ZCARD dev_timeline:draft-task:processing-index
redis-cli -h <REDIS_HOST> --scan --pattern 'dev_timeline:draft-task:*' | wc -l
```

## 3.1) UUID 문자열 schema 전환

구 컨테이너가 완전히 중지된 뒤에만 실행한다. 기존 mapping/owner의 `BINARY(16)` UUID를 canonical
`VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin`으로 보존 전환한다. MySQL DDL은 자동 commit이므로
각 검증이 0건일 때만 다음 묶음으로 진행한다(별도 승인 필수).

```sql
-- BINARY 원본을 보존한 채 문자열 임시 컬럼을 만든다.
ALTER TABLE user_subject_links
    ADD COLUMN subject_id_varchar VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER subject_id;
ALTER TABLE daily_records
    ADD COLUMN subject_id_varchar VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER subject_id;
ALTER TABLE timeline_draft_source_items
    ADD COLUMN subject_id_varchar VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER subject_id;
ALTER TABLE push_registrations
    ADD COLUMN subject_id_varchar VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER subject_id;

UPDATE user_subject_links
SET subject_id_varchar = LOWER(CONCAT(
    SUBSTR(HEX(subject_id), 1, 8), '-', SUBSTR(HEX(subject_id), 9, 4), '-',
    SUBSTR(HEX(subject_id), 13, 4), '-', SUBSTR(HEX(subject_id), 17, 4), '-',
    SUBSTR(HEX(subject_id), 21, 12)));
UPDATE daily_records
SET subject_id_varchar = LOWER(CONCAT(
    SUBSTR(HEX(subject_id), 1, 8), '-', SUBSTR(HEX(subject_id), 9, 4), '-',
    SUBSTR(HEX(subject_id), 13, 4), '-', SUBSTR(HEX(subject_id), 17, 4), '-',
    SUBSTR(HEX(subject_id), 21, 12)))
WHERE subject_id IS NOT NULL;
UPDATE timeline_draft_source_items
SET subject_id_varchar = LOWER(CONCAT(
    SUBSTR(HEX(subject_id), 1, 8), '-', SUBSTR(HEX(subject_id), 9, 4), '-',
    SUBSTR(HEX(subject_id), 13, 4), '-', SUBSTR(HEX(subject_id), 17, 4), '-',
    SUBSTR(HEX(subject_id), 21, 12)))
WHERE subject_id IS NOT NULL;
UPDATE push_registrations
SET subject_id_varchar = LOWER(CONCAT(
    SUBSTR(HEX(subject_id), 1, 8), '-', SUBSTR(HEX(subject_id), 9, 4), '-',
    SUBSTR(HEX(subject_id), 13, 4), '-', SUBSTR(HEX(subject_id), 17, 4), '-',
    SUBSTR(HEX(subject_id), 21, 12)))
WHERE subject_id IS NOT NULL;

-- 네 쿼리 모두 0이어야 한다. mapping은 UUIDv4/RFC variant도 함께 확인한다.
SELECT COUNT(*) FROM user_subject_links
WHERE subject_id_varchar IS NULL
   OR LOWER(REPLACE(subject_id_varchar, '-', '')) <> LOWER(HEX(subject_id))
   OR SUBSTR(subject_id_varchar, 15, 1) <> '4'
   OR SUBSTR(subject_id_varchar, 20, 1) NOT IN ('8', '9', 'a', 'b');
SELECT COUNT(*) FROM daily_records
WHERE (subject_id IS NULL) <> (subject_id_varchar IS NULL)
   OR (subject_id IS NOT NULL
       AND LOWER(REPLACE(subject_id_varchar, '-', '')) <> LOWER(HEX(subject_id)));
SELECT COUNT(*) FROM timeline_draft_source_items
WHERE (subject_id IS NULL) <> (subject_id_varchar IS NULL)
   OR (subject_id IS NOT NULL
       AND LOWER(REPLACE(subject_id_varchar, '-', '')) <> LOWER(HEX(subject_id)));
SELECT COUNT(*) FROM push_registrations
WHERE (subject_id IS NULL) <> (subject_id_varchar IS NULL)
   OR (subject_id IS NOT NULL
       AND LOWER(REPLACE(subject_id_varchar, '-', '')) <> LOWER(HEX(subject_id)));

-- 참조 제약/index를 먼저 내린 뒤 parent와 child를 문자열 컬럼으로 교체한다.
ALTER TABLE daily_records
    DROP FOREIGN KEY fk_daily_records_subject,
    DROP KEY uq_daily_records_subject_date;
ALTER TABLE timeline_draft_source_items
    DROP FOREIGN KEY fk_draft_source_items_subject,
    DROP KEY fk_draft_source_items_subject;
-- 0단계에서 0행을 확인한 document table도 parent FK를 참조하므로 parent 변경 전에 제거한다.
DROP TABLE user_memory_documents;

ALTER TABLE user_subject_links
    DROP KEY uq_user_subject_links_subject,
    DROP COLUMN subject_id,
    CHANGE COLUMN subject_id_varchar subject_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE KEY uq_user_subject_links_subject (subject_id);

ALTER TABLE daily_records
    DROP COLUMN subject_id,
    CHANGE COLUMN subject_id_varchar subject_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uq_daily_records_subject_date (subject_id, record_date),
    ADD CONSTRAINT fk_daily_records_subject
        FOREIGN KEY (subject_id) REFERENCES user_subject_links (subject_id) ON DELETE RESTRICT;
ALTER TABLE timeline_draft_source_items
    DROP COLUMN subject_id,
    CHANGE COLUMN subject_id_varchar subject_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_draft_source_items_subject
        FOREIGN KEY (subject_id) REFERENCES user_subject_links (subject_id) ON DELETE RESTRICT;
ALTER TABLE push_registrations
    DROP COLUMN subject_id,
    CHANGE COLUMN subject_id_varchar subject_id
        VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;

-- 0단계에서 빈 상태를 확인한 기존 table은 복사 없이 PK를 직접 바꾼다.
ALTER TABLE user_memories
    DROP PRIMARY KEY,
    DROP COLUMN user_id,
    ADD COLUMN subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL FIRST,
    ADD PRIMARY KEY (subject_id),
    ADD CONSTRAINT fk_user_memories_subject
        FOREIGN KEY (subject_id) REFERENCES user_subject_links (subject_id) ON DELETE RESTRICT;

SHOW CREATE TABLE user_subject_links;
SHOW CREATE TABLE daily_records;
SHOW CREATE TABLE timeline_draft_source_items;
SHOW CREATE TABLE push_registrations;
SHOW CREATE TABLE user_memories;
```

**실패 시**: 어느 단계까지 자동 commit됐는지 `SHOW CREATE TABLE`로 확인하고 그 단계부터 forward-fix한다.
임시 컬럼 검증이 0이 아니면 원본 `BINARY(16)` 컬럼을 drop하지 않는다. 구 image는 문자열 schema와
호환되지 않으므로 재기동하지 않는다.

## 4) subject backfill 실행

dev WAS에서 2단계의 exact SHA image로 **모드를 하나씩** 실행한다. 도구는 멱등이라 실패 시 원인
교정 후 같은 모드 재실행이 안전하다. 로그는 건수만 남는다(식별자 미출력).

```bash
# dev WAS SSM 세션
ENV_FILE=/home/ubuntu/app/.env
SHA=<2단계에서 기록한 40자 SHA>
DIGEST=<2단계에서 기록한 sha256:64-hex>
REPO_URI=$(aws ecr describe-repositories --repository-names laimory \
  --region ap-northeast-2 --query 'repositories[0].repositoryUri' --output text)
IMG="$REPO_URI@$DIGEST"
aws ecr get-login-password --region ap-northeast-2 \
  | sudo docker login --username AWS --password-stdin "${REPO_URI%/laimory}"
sudo docker pull "$IMG"

# ① users 전 행 mapping 보충(멱등) — 종료 시 users:mappings 1:1 검증, 불일치면 exit 1
sudo docker run --rm --network host --env-file "$ENV_FILE" "$IMG" \
  --spring.main.web-application-type=none --app.push.mode=noop \
  --app.subject.migration.mode=backfill-mappings

# ② owner backfill(멱등) — NULL subject_id 채움, 종료 시 NULL/cross-owner 0건 검증
sudo docker run --rm --network host --env-file "$ENV_FILE" "$IMG" \
  --spring.main.web-application-type=none --app.push.mode=noop \
  --app.subject.migration.mode=backfill-owners
```

**실패 시**: exit 1의 건수 로그로 원인을 좁히고, mapping 누락이면 ①부터, owner 불일치면 원인 교정
후 ②를 재실행한다(둘 다 멱등). 도구 결함이면 forward-fix commit → build-only 재실행 → 새 SHA로 재개.

## 5) PHOTO copy·rewrite (#284 도구)

```bash
# ① legacy → subject namespace S3 copy + 존재·크기·Content-Type 검증(멱등)
sudo docker run --rm --network host --env-file "$ENV_FILE" "$IMG" \
  --spring.main.web-application-type=none --app.push.mode=noop \
  --app.photo.migration.mode=copy-verify

# ② staging/final materialized photoUrl rewrite(단일 transaction, 멱등)
sudo docker run --rm --network host --env-file "$ENV_FILE" "$IMG" \
  --spring.main.web-application-type=none --app.push.mode=noop \
  --app.photo.migration.mode=rewrite-urls
```

**실패 시**: copy-verify는 첫 불일치에서 fail-closed, rewrite-urls는 전체 transaction rollback이라
부분 상태가 없다 — 원인 교정 후 같은 모드 재실행.

## 6) final DDL — subject NOT NULL 확정·legacy nullable화

backfill·rewrite가 모두 성공한 뒤에만 실행한다(별도 승인 필수). 이 DDL이 성공하기 전에는 새 image를
기동하지 않는다.

```sql
ALTER TABLE daily_records
    MODIFY COLUMN subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY COLUMN user_id BIGINT NULL;

ALTER TABLE timeline_draft_source_items
    MODIFY COLUMN subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY COLUMN user_id BIGINT NULL;

ALTER TABLE push_registrations
    MODIFY COLUMN subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY COLUMN user_id BIGINT NULL,
    ADD KEY idx_push_registrations_subject (subject_id);
```

**실패 시**: NULL 잔여 행이 있으면 MODIFY가 거부된다 — 4단계 ②를 재실행해 delta를 채우고 다시
시도한다(forward 전용 — 컬럼을 되돌리지 않는다).

## 7) 최종 검증 — verify-owners + 검증 SQL

```bash
# backfill 없이 검증만 재수행(NULL/cross-owner 불일치면 exit 1)
sudo docker run --rm --network host --env-file "$ENV_FILE" "$IMG" \
  --spring.main.web-application-type=none --app.push.mode=noop \
  --app.subject.migration.mode=verify-owners
```

```sql
-- 값 비출력 — count만 기록한다
SELECT COUNT(*) FROM users;                -- (a)
SELECT COUNT(*) FROM user_subject_links;   -- (b) = (a) 이어야 한다 (mapping 1:1)
SELECT COUNT(*) FROM daily_records                WHERE subject_id IS NULL;  -- 0
SELECT COUNT(*) FROM timeline_draft_source_items  WHERE subject_id IS NULL;  -- 0
SELECT COUNT(*) FROM push_registrations           WHERE subject_id IS NULL;  -- 0
SELECT COUNT(*) FROM user_memories WHERE subject_id IS NULL;                 -- 0
```

`verify-owners`는 각 legacy `user_id`를 in-process mapping으로 해석해 세 owner 테이블의
cross-owner 불일치를 값 출력 없이 집계한다. 아래 SQL은 운영자가 남기는 보조 count다.

**실패 시**: 불일치는 새 image 기동 금지 사유다. 원인을 좁혀 4~6단계를 재실행한다.

## 8) legacy Redis namespace 폐기

pre-release라 보존할 사용자 상태가 없다는 전제의 **의도적 삭제**다(별도 승인 필수). 삭제 전
key/member count를 기록한다. `RedisGateway`에 SCAN primitive가 없어 코드가 아닌 수동 redis-cli
절차로 수행한다. dev 환경 prefix는 `dev_`(`.env`의 `REDIS_KEY_PREFIX`)다.

```bash
# ① count 기록 (값 비출력 — 건수만)
redis-cli -h <REDIS_HOST> ZCARD dev_timeline:draft-task:processing-index
redis-cli -h <REDIS_HOST> ZCARD dev_timeline:user-memory-update:pending
redis-cli -h <REDIS_HOST> --scan --pattern 'dev_timeline:draft-task:*' | wc -l
redis-cli -h <REDIS_HOST> --scan --pattern 'dev_timeline:user-memory-update:*' | wc -l

# ② 삭제 — legacy user-owner 형식의 draft task/index·User Memory pending/guard/task 전부
redis-cli -h <REDIS_HOST> --scan --pattern 'dev_timeline:draft-task:*' \
  | xargs -r -n 100 redis-cli -h <REDIS_HOST> DEL
redis-cli -h <REDIS_HOST> --scan --pattern 'dev_timeline:user-memory-update:*' \
  | xargs -r -n 100 redis-cli -h <REDIS_HOST> DEL

# ③ 잔여 0건 확인
redis-cli -h <REDIS_HOST> --scan --pattern 'dev_timeline:draft-task:*' | wc -l
redis-cli -h <REDIS_HOST> --scan --pattern 'dev_timeline:user-memory-update:*' | wc -l
```

기존 Redis owner 데이터는 subject 형식으로 이관하지 않는다. `auth:*`·Spring Session namespace는
대상이 아니다 — 건드리지 않는다.

## 9) deploy-existing으로 새 image 기동과 검증

build-only한 **같은 SHA+digest**를 새 build 없이 digest로 배포한다(pause 중에도 manual dispatch는
실행된다). workflow가 dispatch 시점의 SHA tag digest를 기록값과 대조한 뒤에만 SSM을 전송한다.

```bash
gh workflow run deploy.yml --repo soma17th-369/Laimory-server \
  -f image_sha="$SHA" -f image_digest="$DIGEST"
```

배포 workflow의 preflight·health gate(90초 `/api/v1/intro` polling) 통과 후 추가로 확인한다.

```bash
# dev WAS SSM 세션 — health
curl -fsS http://localhost:8080/api/v1/intro > /dev/null && echo OK
```

- 7단계 검증 SQL 재실행(count 불변 확인 — 특히 mapping 1:1, NULL 0건)
- 소유권: 테스트 계정 로그인 → timeline 조회·draft 생성 → 본인 데이터만 보이는지 확인
- FCM: push 등록 API 호출 후 `push_registrations` count 증가 확인(값 비출력)
- PHOTO: 기존 timeline의 photoUrl(subject namespace)이 CDN에서 200으로 서빙되는지,
  presign 업로드→조회 왕복이 성공하는지 확인

**실패 시**: forward-fix commit → build-only 재실행 → 새 SHA로 deploy-existing 재실행.
구 image 재기동(rollback)은 하지 않는다.

## 10) pause 해제와 legacy 즉시 삭제

9단계 검증을 모두 통과한 뒤에만 진행한다.

```bash
gh variable set DEPLOY_PAUSED --repo soma17th-369/Laimory-server --body false
```

이후 **각각 별도 승인** 하에 legacy를 즉시 삭제한다(관찰 기간 없음 — 계획 §5.6 폐기 결정).
이 삭제가 끝나면 phase-1 image로의 code rollback은 불가능하다.

```bash
# ① 구 photo object 삭제(사전 승인 필수) — users 전 행을 순회해 source↔target의
#    크기·Content-Type을 전부 다시 검증한 뒤 source를 삭제하고 전체 legacy 잔여 0건 확인
sudo docker run --rm --network host --env-file "$ENV_FILE" "$IMG" \
  --spring.main.web-application-type=none --app.push.mode=noop \
  --app.photo.migration.mode=delete-legacy
```

`delete-legacy`는 전체 target 동등성 검증이 끝나기 전에는 어떤 source도 삭제하지 않는다. S3 verbose
delete 응답의 error·미보고 key 또는 삭제 후 known-user legacy prefix 잔여가 하나라도 있으면 exit 1이다.
성공 로그의 `objectsVerified=objectsDeleted`, `objectsRemaining=0`을 확인한 뒤에만 legacy DB 삭제로 간다.

```sql
-- ② legacy user_id 컬럼 삭제(사전 승인 필수).
--    다중 컬럼 UNIQUE/index는 컬럼 DROP 전에 명시적으로 지운다(잔여 축소 index 방지).
ALTER TABLE daily_records
    DROP KEY uq_daily_records_user_date,
    DROP COLUMN user_id;
ALTER TABLE timeline_draft_source_items
    DROP COLUMN user_id;
ALTER TABLE push_registrations
    DROP KEY idx_push_registrations_user,
    DROP COLUMN user_id;
```

- ③ migration 도구 일괄 제거 PR을 만든다 — `timeline/photo/migration/`·`user/migration/` 패키지,
  `UserRepository.findAllUserIds` 등 migration 전용 메서드, `PhotoObjectKeys` legacy 함수
  (`fullKey(userId,…)`/`sha256hex`), `SubjectMappingService.createIfAbsent`, `schema.sql`의 legacy
  legacy `user_id` 컬럼과 이 runbook의 관련 절차. `user_memories`는 subject PK 정본으로 유지한다.
