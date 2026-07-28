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
- `daily_records → timeline_events ⇄ timeline_items` (`timeline_event_items` junction N:M —
  Item은 record/event FK가 없는 독립 행이고 하루 범위는 junction→Event→DailyRecord로 해석)
- `timeline_photo_delete_jobs` (마지막 참조가 사라진 PHOTO Item과 S3 삭제 의무, 행 존재=대기,
  성공 시 Item과 행 삭제)
- `timeline_draft_source_items` (API→AI 입력 staging, `(task_id, raw_id)` UNIQUE)
- `users`, `refresh_tokens`
- `push_registrations`

`schema.sql`은 빈 Docker MySQL volume의 최초 초기화와 새 Terraform MySQL bootstrap에 쓰인다.
`CREATE TABLE IF NOT EXISTS`라 기존 table을 변경하지 않으며 migration framework는 없다.
기존 dev/prod DB 변경은 애플리케이션 배포 전에 수동 DDL과 검증이 필요하다.
dev는 `dev` 브랜치 push가 자동 배포를 트리거하므로(`.github/workflows/deploy.yml` — 구 컨테이너
중단 후 새 컨테이너 기동), 스키마 변경 PR의 live DDL은 **머지 전에** dev DB에 적용해야 한다.
미적용 상태로 머지하면 새 앱이 `ddl-auto=validate` 기동 실패로 dev가 다운된다.

Terraform은 schema를 S3 bootstrap object로 올려 새 MySQL instance의 user data에서 적용한다.
기존 MySQL은 `user_data` 변경을 ignore하므로 Terraform 파일 변경만으로 live schema가 바뀌지 않는다.

JPA auditing이 created/updated time을 채우지만 authenticated auditor가 없어 `modified_by`는 NULL이다.
final 테이블(`timeline_events`/`timeline_items`)은 API JPA와 AI raw INSERT 두 writer가 쓴다. API writer는
Event PATCH의 Event/memo 수정과 수동 PHOTO Item/junction 추가를 한 transaction으로 commit한다 —
감사 timestamp는 DB default(`CURRENT_TIMESTAMP(6)`)가 겸하고(AI는 컬럼 생략 가능), AI는 `modified_by`에
`'AI'`를 명시한다(provenance — 재실행 삭제 조건으로 쓰지 않는다). `timeline_event_items`는 순수 연결
행이라 감사 컬럼이 없다.

`timeline_photo_delete_jobs`는 object registry가 아닌 순수 작업 테이블이다. `timeline_item_id`와 full
`object_key`는 각각 UNIQUE이며, 기본 RESTRICT FK의 `timeline_item_id`가 보존 중인 원문 PHOTO Item을
가리킨다. native `INSERT IGNORE`가 Item/object 중복 enqueue를 원자적으로 no-op하며 timestamp를 직접
채운다. worker는 `created_at, timeline_photo_delete_job_id` oldest-first로 읽고, S3 성공 job을 먼저
지운 뒤 해당 Item을 같은 transaction에서 지운다. Item 삭제가 실패하면 job 삭제도 rollback된다.
상태·시도 횟수·backoff·lease·error·완료 이력 column은 없다.

`push_registrations`(#174)는 사용자 1:N FCM 등록(FID)이다. `firebase_installation_id`는 전역 UNIQUE로
한 시점 단일 owner를 강제하고, 대소문자 구분 opaque 식별자라 **컬럼 단위** `utf8mb4_bin` collation을
쓴다(테이블 기본은 `_unicode_ci` — 저장소 유일한 컬럼 collation). `user_id`는 기존 방침대로 FK 없는
soft-owner다. 행 존재 = 활성 등록이며 해제·영구 무효는 행 삭제다. 쓰기는 repository의
`INSERT ... ON DUPLICATE KEY UPDATE` native upsert(저장소 첫 native query — 등록·계정 전환 재결합을
한 문장으로 원자화, read-then-insert+unique 예외 복구 금지)와 조건부 delete뿐이라 JPA auditing이
돌지 않고 감사 컬럼(`created_at`/`updated_at`)은 upsert SQL이 직접 채운다(`modified_by` NULL).
entity는 조회·validate용 read model이다. live dev/prod 반영은 앱 배포 전 수동 `CREATE TABLE`이 필요하다.

`timeline_events.event_type`은 `VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'`이다(#166). default는 기존 행
backfill과 컬럼을 생략하는 writer의 INSERT 호환용이다. entity는 `@Enumerated(STRING)`
`TimelineEventType`이며, AI direct-write는 allowlist literal만 INSERT한다(미지원 literal은 AI validation
FAILED — 새 literal 활성화 순서는 "Server enum 배포 → AI writer 활성화").

### Redis

application-owned access는 `RedisGateway`를 거친다.

| Logical key/namespace | Purpose | Lifetime |
|---|---|---|
| `timeline:draft-task:{taskId}` | draft state (세 상태 모두 owner `userId`·선생성 `dailyRecordId`·token hash 필수, PROCESSING에만 `timelineWindow`·필수 `processingStartedAt` 포함). FAILED `error`는 JSON number이며 문자열 코드와 필수 필드가 빠진 shape는 역직렬화를 거부한다. null·미지 numeric error는 polling에서 `-1011`로 수렴한다. PROCESSING 만료는 key 소멸이며 FAILED 전이가 아니다. | PROCESSING 3m, terminal 24h |
| `timeline:draft-task:processing-index` | stuck PROCESSING 관측용 sorted set(member=taskId, score=processingStartedAt epoch ms). task JSON 저장+ZADD와 terminal 저장+ZREM은 Lua 원자 연산이며, read 때 PROCESSING TTL 밖 member를 정리한다. task key가 권위이고 index는 상태 판정에 쓰지 않는다. | key TTL 없음; member는 terminal 전이 또는 3m cutoff 관측 때 제거 |
| `timeline:callback-token-uses:{taskId}` | callback token 소비 marker. hash 검증 직후 `SET NX`로 고정값 `used`를 저장하며 raw token/hash는 넣지 않는다. 소비 뒤 후속 처리 실패에도 삭제·환불하지 않는다. | 25h |
| `auth:app-code:{sha256hex}` | one-time App Code | 60s |
| `${REDIS_KEY_PREFIX}spring:session` | OAuth handshake session namespace | 5m |

`RedisGateway`가 `app.redis.key-prefix`를 붙이므로 호출자는 logical key만 넘긴다.
단순 get/set/delete 외에 callback token marker 등에 쓰는 `setIfAbsent`(SET NX + TTL)를 제공한다.
Timeline task는 값 PSETEX와 PROCESSING index ZADD/ZREM도 Lua 한 경계에서 수행한다.
logical key는 `{feature}:{entity}:{id}` namespace 형태로 만들고 feature store의 상수에서 조립한다.
호출부 key에 `dev_` 같은 environment prefix를 hardcode하지 않는다.
dev는 공유 Redis에서 `dev_` prefix를 쓰고 local/prod 기본값은 빈 문자열이다.
Spring Session은 framework-managed 영역이며 namespace 설정으로 격리한다.

과거 같은 날짜 작업 admission에 쓰던 `timeline:date-guard:*` key는 더 이상 application key 계약이
아니다. 배포 전에 남은 key는 읽거나 일괄 삭제하지 않으며 설정돼 있던 TTL로 자연 만료한다.

### S3

사진 object body를 저장하고 DB JSON payload에는 `filename`, client URI와 materialized CDN URL을 둔다.
full key는 `{sha256hex(userId)}/photos/{filename}`이며 DB column으로 저장하지 않는다.
Event PATCH의 수동 PHOTO는 client가 업로드 완료 뒤 보내므로 서버가 object 존재를 조회하지 않는다.
해당 입력에는 `description`·`photoUrl`이 없고, 저장 시 `description=null`과 서버가 materialize한 CDN URL을
쓴다.

삭제는 두 경로다. draft cleanup은 단건 `DeleteObject`(전역 client 설정 그대로) 뒤 source row를 지운다.
Event/DailyRecord 삭제는 root/junction/non-PHOTO orphan hard delete와 함께 MySQL job을 만들고 유효한
orphan PHOTO Item을 보존한 뒤 즉시 성공하며, 별도 worker가
`DeleteObjects` 배치(최대 1,000 key/request, verbose, 요청 단위 apiCallTimeout 10s·
apiCallAttemptTimeout 3s)를 transaction 밖에서 호출한다. `Deleted`로 확인된 job과 그 PHOTO Item만
별도 transaction에서 지우고 객체별 Error·응답 누락·SDK 예외면 두 행을 남겨 재시도한다. PHOTO payload가
깨졌거나 filename/object key를 만들 수 없으면 job을 건너뛰고 손상 Item의 hard delete는 진행한다
(orphan 허용).

## Invariants

- entity와 `schema.sql`을 함께 변경하고 running DB rollout을 별도로 계획한다.
- Event↔Item 연결은 `timeline_event_items` junction이 유일 경로다. 같은 DailyRecord 안에서만 Item을
  공유한다는 규칙은 DB 제약이 아니라 writer 계약이다. AI·fake는 새 Item을 현재 task의 새 Event에만
  연결하고, Event PATCH는 같은 record의 기존 PHOTO Item을 대상 Event에 재사용할 수 있다.
- `timeline_items.raw_id`는 DB UNIQUE가 없다 — draft는 API 사전 제외 + AI write 직전 재검사로 방어하고,
  Event PATCH는 request rawId를 첫 항목 우선으로 dedupe한 뒤 같은 record의 PHOTO를 재사용한다. 대상
  Event에 이미 연결된 PHOTO는 no-op이고 같은 rawId의 non-PHOTO는 400이다. legacy로 같은 rawId의 PHOTO가
  여러 행이면 대상 Event에 연결된 행을 우선하고, 없으면 가장 작은 Item ID를 선택한다. race/legacy 중복
  행은 허용하며 조회·삭제는 `timeline_item_id` 기준이다.
- `raw_id`(source·final 둘 다)는 대소문자 구분 opaque 식별자라 **컬럼 단위 `utf8mb4_bin` collation**을 쓴다
  (FID 선례와 동일; 테이블 기본 `_unicode_ci`와 다름). 서버 dedupe(Java String)·기존 rawId 제외(HashSet/IN)와
  DB 비교 규칙을 일치시켜, `(task_id, raw_id)` UNIQUE가 `abc`/`ABC`를 다른 값으로 취급하게 한다(불일치 시 앱
  dedupe를 통과한 뒤 DB duplicate-key 500이 나거나 final 제외 결과가 어긋난다).
- `item_type`과 `raw_id`는 JSON payload 밖의 권위 column이다.
- application Redis 접근은 `RedisGateway`를 우회하지 않는다.
- staging retention은 PROCESSING TTL보다 충분히 길어야 한다.
- 만료 PHOTO staging은 S3 삭제 성공 뒤 row를 삭제하고 실패 시 row를 남긴다.
- Event/DailyRecord 삭제는 필요한 PHOTO job insert·PHOTO Item 보존과 root/junction/non-PHOTO hard
  delete를 같은 transaction으로 commit한다.
  Event/Record 행 삭제 시 자기 junction은 DB FK `ON DELETE CASCADE`로 소멸하고(JPA cascade 없음),
  Item은 record FK가 없어 cascade되지 않으므로 삭제 대상에만 연결된 orphan을 같은 트랜잭션에서
  분류한다. non-PHOTO와 job을 만들 수 없는 손상 PHOTO만 즉시 삭제하고, 유효한 PHOTO는 job과 함께
  보존한다(다른 Event에도 연결된 shared Item·PHOTO는 유지). S3 작업 권위는 MySQL job row이며 worker
  성공 시 Item과 row를 한 transaction에서 삭제하고 실패 시 둘 다 보존한다.

## Known Gaps

- Flyway/Liquibase와 자동 schema rollout이 없다.
- finalized photo와 presign 후 staging이 없는 orphan object는 cleanup 대상이 아니다.
- authenticated auditor propagation이 없다.
- 같은 날짜 draft·수동 PHOTO 추가·삭제 사이의 공통 admission과 경합 정합성 보장은 미구현이다.

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
