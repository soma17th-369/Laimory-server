# Persistence

## Scope

MySQL schema/JPA, Redis key·TTL·namespace와 S3에 걸친 저장 규칙과 schema rollout 제약을 설명한다.

## Read When

entity, repository, table/index/FK, Redis key/value/TTL, photo object 또는 cleanup을 바꿀 때 읽는다.

## Authoritative Sources

- `src/main/resources/db/schema.sql`
- `src/main/resources/application*.properties`
- `src/main/java/com/laimory/server/**/entity/*.java`, repositories
- `BaseEntity`, `JpaAuditingConfig`, `RedisGateway`
- timeline task/photo cleanup services and stores
- `docker-compose.yml`, `terraform/storage_cdn.tf`, `terraform/ec2.tf`,
  `terraform/user_data/mysql.sh.tftpl`

## Current Implementation

### MySQL

MySQL 8과 JPA/Hibernate를 사용하며 `spring.jpa.hibernate.ddl-auto=validate`다.
애플리케이션은 schema를 생성·변경하지 않는다.

주요 저장 영역:

- `app_config`
- `daily_records → timeline_events → timeline_items`
- `timeline_draft_source_items`, `timeline_draft_event_suggestions`
- `users`, `refresh_tokens`

`schema.sql`은 빈 Docker MySQL volume의 최초 초기화와 새 Terraform MySQL bootstrap에 쓰인다.
`CREATE TABLE IF NOT EXISTS`라 기존 table을 변경하지 않으며 migration framework는 없다.
기존 dev/prod DB 변경은 애플리케이션 배포 전에 수동 DDL과 검증이 필요하다.

Terraform은 schema를 S3 bootstrap object로 올려 새 MySQL instance의 user data에서 적용한다.
기존 MySQL은 `user_data` 변경을 ignore하므로 Terraform 파일 변경만으로 live schema가 바뀌지 않는다.

JPA auditing이 created/updated time을 채우지만 authenticated auditor가 없어 `modified_by`는 NULL이다.
AI가 raw INSERT하는 suggestion staging은 DB default audit time을 사용한다.

### Redis

application-owned access는 `RedisGateway`를 거친다.

| Logical key/namespace | Purpose | Lifetime |
|---|---|---|
| `timeline:draft-task:{taskId}` | draft state/result (SUCCESS에만 `dailyRecordId` 포함, PROCESSING에만 `processingStartedAt`(UTC ISO-8601 — polling 경과 시간 기준, terminal 폐기) 포함 — 두 필드 모두 NON_NULL이라 없는 상태에선 key 미노출; 배포 전 legacy PROCESSING은 필드 부재 → null 역직렬화) | PROCESSING 1h, terminal 24h |
| `timeline:callback-token-uses:{taskId}` | callback token use state | 25h |
| `timeline:date-guard:{userId}:{recordDate}` | 같은 날짜 동시 작업 lease — 값은 holder(draft `task:{taskId}`, 삭제 `delete:{operationId}`) | 1h (PROCESSING 저장 성공 시 재갱신, 삭제는 모든 종료 경로에서 해제) |
| `auth:app-code:{sha256hex}` | one-time App Code | 60s |
| `${REDIS_KEY_PREFIX}spring:session` | OAuth handshake session namespace | 5m |

`RedisGateway`가 `app.redis.key-prefix`를 붙이므로 호출자는 logical key만 넘긴다.
단순 get/set/delete 외에 원자 연산을 제공한다: `setIfAbsent`(SET NX + TTL),
`expireIfValueMatches`/`deleteIfValueMatches`(Lua — 값 비교와 PEXPIRE/DEL을 원자화해
만료→재선점 경합에서 남의 lease를 갱신·삭제하지 않는다).
logical key는 `{feature}:{entity}:{id}` namespace 형태로 만들고 feature store의 상수에서 조립한다.
호출부 key에 `dev_` 같은 environment prefix를 hardcode하지 않는다.
dev는 공유 Redis에서 `dev_` prefix를 쓰고 local/prod 기본값은 빈 문자열이다.
Spring Session은 framework-managed 영역이며 namespace 설정으로 격리한다.

### S3

사진 object body를 저장하고 DB JSON payload에는 `filename`, client URI와 materialized CDN URL을 둔다.
full key는 `{sha256hex(userId)}/photos/{filename}`이며 DB column으로 저장하지 않는다.

삭제는 두 경로다. draft cleanup은 단건 `DeleteObject`(전역 client 설정 그대로),
Event/DailyRecord 삭제는 `DeleteObjects` 배치(최대 1,000 key/batch 순차, 요청 단위
override로 apiCallTimeout 10s·apiCallAttemptTimeout 3s)를 쓴다. 배치는 SDK 예외 또는
객체별 error 1건이라도 있으면 `ERROR_1017`로 실패하고 DB 삭제를 시작하지 않는다
(DB 보존 → 재시도 수렴). PHOTO payload가 깨졌거나 filename이 없으면 S3만 건너뛰고
행 삭제는 진행한다(orphan 허용 — cleanup과 동일 규칙).

## Invariants

- entity와 `schema.sql`을 함께 변경하고 running DB rollout을 별도로 계획한다.
- staging source→suggestion은 hard FK가 아닌 soft reference이며 assembler가 같은 task인지 검증한다.
- `item_type`과 `raw_id`는 JSON payload 밖의 권위 column이다.
- application Redis 접근은 `RedisGateway`를 우회하지 않는다.
- staging retention은 PROCESSING TTL보다 충분히 길어야 한다.
- 만료 PHOTO staging은 S3 삭제 성공 뒤 row를 삭제하고 실패 시 row를 남긴다.
- Event/DailyRecord 삭제는 S3 배치 삭제가 전부 성공한 뒤에만 DB row를 삭제한다
  (하위 행은 DB FK `ON DELETE CASCADE` — JPA cascade 없음).

## Known Gaps

- Flyway/Liquibase와 자동 schema rollout이 없다.
- finalized photo와 presign 후 staging이 없는 orphan object는 cleanup 대상이 아니다.
- authenticated auditor propagation이 없다.

## Update When

table/entity/audit, schema bootstrap·rollout, Redis key/value/TTL/prefix/session 또는 photo key/cleanup이 바뀔 때
갱신한다.

## Validation

```bash
./gradlew test
docker compose up -d
./gradlew integrationTest
```

빈 DB bootstrap 검증이 꼭 필요할 때만 local data 삭제를 확인받고
`docker compose down -v` 뒤 다시 올린다.
