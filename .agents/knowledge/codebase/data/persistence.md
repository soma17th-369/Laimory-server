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
- `docker-compose.yml`, `.github/workflows/deploy.yml`

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
- `timeline_draft_source_items` (API→AI 입력 staging, `(task_id, raw_id)` UNIQUE — payload는
  v1 privacy 치환 저장본이고 `clientPhotoUri`만 원문 유지)
- `users`, `refresh_tokens`
- `user_subject_links` (인증 사용자↔콘텐츠 subject HMAC 매핑 — raw `user_id` 미저장)
- `user_memories` (subject당 1행 opaque JSON 문서, 행 존재=메모리 있음)
- `push_registrations`

`schema.sql`은 빈 Docker MySQL volume의 최초 초기화에 쓰인다.
`CREATE TABLE IF NOT EXISTS`라 기존 table을 변경하지 않으며 migration framework는 없다.
기존 dev/prod DB 변경은 애플리케이션 배포 전에 수동 DDL과 검증이 필요하다.
dev는 `dev` 브랜치 push가 자동 배포를 트리거하므로(`.github/workflows/deploy.yml` — 구 컨테이너
중단 후 새 컨테이너 기동), 스키마 변경 PR의 live DDL은 **머지 전에** dev DB에 적용해야 한다.
미적용 상태로 머지하면 새 앱이 `ddl-auto=validate` 기동 실패로 dev가 다운된다.

저장소는 신규 AWS MySQL 초기화를 자동화하지 않는다. live MySQL schema는 저장소 변경만으로 바뀌지
않으며, 애플리케이션 배포 전에 실제 DB 상태를 확인하고 수동 DDL을 적용해야 한다.

JPA auditing이 created/updated time을 채우지만 authenticated auditor가 없어 `modified_by`는 NULL이다.
final 테이블(`timeline_events`/`timeline_items`)의 writer는 API JPA 하나뿐이다 — AI 결과도 서버 결과 저장
transaction이 쓰고, Event PATCH의 Event/memo 수정과 수동 PHOTO Item/junction 추가도 같은 계층이 commit한다.
timestamp DB default(`CURRENT_TIMESTAMP(6)`)는 과거 AI raw INSERT 계약의 잔재로 남아 있으며 무해하다.
`timeline_event_items`는 순수 연결 행이라 감사 컬럼이 없다. junction 행 삭제는 root(Event/Item) 삭제의
FK cascade가 기본이고, Event-Item 연결 해제만 영향 행 수를 반환하는 직접 DELETE로 명시 삭제한다.

`timeline_draft_source_items`의 draft 준비 hot path INSERT는 생성 ID를 같은 transaction에서 사용하지 않으므로
전용 `JdbcTemplate` batch writer가 입력 순서대로 저장한다. Connector/J가 batch를 multi-values INSERT로
재작성하도록 기본/docker JDBC URL 모두 `rewriteBatchedStatements=true`를 사용한다. 이 native writer는 JPA
auditing을 우회하므로 Spring Data auditing과 같은 app `LocalDateTime.now()`를 batch 시작 전에 한 번 캡처해
`created_at`/`updated_at` 파라미터로 바인딩하고 `modified_by`는 NULL로 둔다. task 단위 조회·채택
삭제·cleanup은 기존 JPA repository가 담당한다.

`timeline_photo_delete_jobs`는 object registry가 아닌 순수 작업 테이블이다. `timeline_item_id`와 full
`object_key`는 각각 UNIQUE이며, 기본 RESTRICT FK의 `timeline_item_id`가 보존 중인 원문 PHOTO Item을
가리킨다. native `INSERT IGNORE`가 Item/object 중복 enqueue를 원자적으로 no-op하며 timestamp를 직접
채운다. worker는 checked-in default인 매일 03:00 `Asia/Seoul`(cron/zone 환경 override 가능)에
`created_at, timeline_photo_delete_job_id` oldest-first 최대 1,000개를 한 번 읽고, S3 성공 job을 먼저
지운 뒤 해당 Item을 같은 transaction에서 지운다. 실패분과 1,000개 초과분은 기본 cadence상 다음 날
실행까지 남는다. 실행 시각에 애플리케이션이 내려가 있어도 catch-up하지 않으며, Item 삭제가 실패하면
job 삭제도 rollback된다. 상태·시도 횟수·backoff·lease·error·완료 이력 column은 없다.

`push_registrations`(#174)는 subject 1:N FCM 등록(FID)이다. `firebase_installation_id`는 전역 UNIQUE로
한 시점 단일 owner를 강제하고, 대소문자 구분 opaque 식별자라 **컬럼 단위** `utf8mb4_bin` collation을
쓴다(테이블 기본은 `_unicode_ci`). `subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin`이 FK 없는
owner이고 조회 index를 가진다. 행 존재 = 활성 등록이며 해제·영구 무효는 행 삭제다. 쓰기는 repository의
`INSERT ... ON DUPLICATE KEY UPDATE` native upsert(저장소 첫 native query — 등록·계정 전환 재결합을
한 문장으로 원자화, read-then-insert+unique 예외 복구 금지)와 조건부 delete뿐이라 JPA auditing이
돌지 않고 감사 컬럼(`created_at`/`updated_at`)은 upsert SQL이 직접 채운다(`modified_by` NULL).
entity는 조회·validate용 read model이다. live dev/prod 반영은 앱 배포 전 수동 `CREATE TABLE`이 필요하다.

`user_memories`는 subject별 User Memory 문서다. `subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin`이
PK인 subject당 1행이고
`memory`는 `JSON NOT NULL`이며, 행 존재 = 메모리 있음이고 제거는 행 삭제다. entity는
`@JdbcTypeCode(SqlTypes.JSON)`
`JsonNode`(`timeline_items.payload`와 같은 매핑)이고 서버는 문서 구조·스키마를 해석·정규화하지 않지만,
저장 직전 textual leaf를 v1 privacy 치환한다(구조 불변).

`users`의 컬럼이 아니라 별도 테이블인 이유는 문서 크기다 — JPA 엔티티 로드는 항상 전 컬럼을 SELECT하므로
컬럼으로 두면 로그인의 `User` 조회가 매번 blob을 함께 읽는다. 테이블을 나눠 `User`를 읽는 어떤 경로도
문서에 닿지 않게 한다. 두 entity 사이에 JPA 연관 매핑을 두지 않는 것이 이 분리의 전제다(저장소 전체
방침과 동일 — `@OneToOne`은 기본 EAGER이고 역방향은 지연 로딩이 불가능해 분리 효과가 사라진다).
접근은 service가 Java `UUID`를 `UserMemoryRepository.findById(subjectId)`로 전달하는 경로뿐이다.
Hibernate UUID JDBC mapping은 `VARCHAR`로 명시하며 별도 subject wrapper나 converter를 두지 않는다.

쓰기는 repository의 native `INSERT ... ON DUPLICATE KEY UPDATE` upsert와 조건부 delete뿐이라(같은
사용자 동시 저장의 PK 중복을 한 문장으로 원자화 — `push_registrations` 선례) JPA auditing이 돌지 않고
감사 컬럼은 upsert SQL이 직접 채운다(`modified_by` NULL). entity는 조회·validate용 read model이다.
갱신은 문서 전체 교체뿐이고 부분 병합·JSON path 수정은 없다. Java `null`과 JSON `null`은 모두 행
삭제로 수렴한다.

`user_subject_links`(#282)는 인증 사용자와 콘텐츠 subject의 매핑이다. raw `user_id`를 저장하지 않고
`HMAC-SHA-256(secret, "content-subject-lookup:v1" || userId 8-byte BE)` 결과가 `user_lookup_key BINARY(32)`
PK이며, `subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin`(CSPRNG UUIDv4 canonical lowercase,
UNIQUE)과 `lookup_key_version SMALLINT`만 갖는다. 감사 컬럼·auto-increment·`BaseEntity` 상속이 의도적으로
없다. lookup key만 `BINARY(32)`/`byte[]`이고 subject는 Java `UUID`를 VARCHAR JDBC type으로 매핑한다.
HMAC key는 배포에서 Secrets
Manager 기동 1회 로드(`app.subject.mode=secretsmanager`), 로컬/테스트는 docker 프로필의 fixture key다
(`fixture` 모드 — provider 선택은 mode property 단일 축이고, fixture-key 기본값은 docker 프로필만
소유해 배포 기본 프로필의 fixture는 무기본값으로 기동 실패하며, mode 미설정도 기동 실패). 접근은 `SubjectMappingService` 한
곳뿐이고(arch test 강제) 일반 경로는 `getRequired()`가 누락을 자동 생성 없이 fail-closed한다. 신규
사용자는 `NewUserProvisioner`가 user insert와 mapping insert를 한 transaction으로 커밋한다(mapping 실패
= user rollback). rotation은 previous key hit 때 PK·version만 native UPDATE로 원자 교체한다(subject 불변).

콘텐츠 owner는 Java `UUID subjectId`다. `daily_records`의 canonical UUID 문자열 `subject_id`는 NOT NULL이며
`uq_daily_records_subject_date (subject_id, record_date)` UNIQUE·FK RESTRICT를 가진다.
`timeline_draft_source_items.subject_id`도 NOT NULL·FK RESTRICT이고,
`push_registrations.subject_id`는 NOT NULL·조회 index를 가지되 기존 soft-owner 방침대로 FK는 없다.
`user_memories`는 subject PK·FK RESTRICT다. subject FK는 `user_subject_links.subject_id`를
`ON DELETE RESTRICT`로 참조한다 — mapping 삭제가 콘텐츠를 암묵 cascade하지 않게 하며, 탈퇴는 콘텐츠
명시 삭제 후 mapping을 마지막에 지우는 계약이다. 이 네 owner 테이블에는 raw `user_id` 컬럼이 없고
runtime repository/entity도 subject만 읽고 쓴다.

`timeline_events.question`은 `VARCHAR(255) NULL`이다(#252). AI 결과 저장 transaction만 쓰는 컬럼이라
편집 API 경로는 값을 건드리지 않으며, 기존 행은 backfill하지 않고 NULL로 남는다. entity는 length 지정
없는 `String`(Hibernate 기본 255)이라 nullable 컬럼을 앱 배포 전에 먼저 추가해야 `ddl-auto=validate`가
통과한다.

`timeline_events.event_type`은 `VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'`이다(#166). default는 기존 행
backfill과 컬럼을 생략하는 writer의 INSERT 호환용이다. entity는 `@Enumerated(STRING)`
`TimelineEventType`이며, 결과 저장 transaction은 allowlist literal만 INSERT한다(미지원 literal은 결과 저장
400 — 새 literal 활성화 순서는 "Server enum 배포 → AI writer 활성화").

### Redis

application-owned access는 `RedisGateway`를 거친다.

| Logical key/namespace | Purpose | Lifetime |
|---|---|---|
| `timeline:draft-task:{taskId}` | draft state (세 상태 모두 owner UUIDv4 subject·선생성 `dailyRecordId`·단계별 token hash 셋 보존, PROCESSING에만 `timelineWindow`·필수 `processingStartedAt` 포함). FAILED `error`는 JSON number이며 문자열 코드와 필수 필드가 빠진 shape는 역직렬화를 거부한다. null·미지 numeric error는 polling에서 `-1011`로 수렴한다. PROCESSING 만료는 key 소멸이며 FAILED 전이가 아니다. | PROCESSING 3m(입력 조회·결과 저장 성공마다 재확보), terminal 24h |
| `timeline:draft-task:processing-index` | stuck PROCESSING 관측용 sorted set(member=taskId, score=processingStartedAt epoch ms). task JSON 저장+ZADD와 terminal 저장+ZREM은 Lua 원자 연산이며, read 때 PROCESSING TTL 밖 member를 정리한다. task key가 권위이고 index는 상태 판정에 쓰지 않는다. | key TTL 없음; member는 terminal 전이 또는 3m cutoff 관측 때 제거 |
| `timeline:draft-task:user:{canonicalUuid(subjectId)}:processing` | subject별 진행 작업 조회 index sorted set(member=taskId, score=processingStartedAt epoch ms). PROCESSING 저장 Lua가 task JSON·전역 index와 함께 ZADD하고 key TTL을 PROCESSING TTL로 갱신하며, terminal 저장 Lua가 ZREM한다. task JSON status/owner가 권위 — 목록 조회는 후보마다 JSON을 검증해 만료·terminal·타인 소유 member를 제외하고 best-effort ZREM한다(역직렬화 불가 JSON은 500·자동 삭제 금지). | key TTL 3m — 새 PROCESSING 저장마다 갱신(마지막 생성 뒤 inactivity cleanup이지 member별 TTL 아님); member는 terminal 전이·목록 조회 lazy prune 때 제거 |
| `timeline:user-memory-update:pending` | 미반영 날짜 sorted set(member=`canonicalUuid(subjectId):dailyRecordId`, score=최초 대기 epoch ms) | 기본 30d retention/TTL |
| `timeline:user-memory-update:user:{canonicalUuid(subjectId)}` | subject별 갱신 guard(`SET NX`) | PROCESSING 3m |
| `timeline:user-memory-update:{taskId}` | User Memory 작업 JSON(owner UUIDv4 subject, 대상 record IDs, base digest) | PROCESSING 3m |
| `auth:app-code:{sha256hex}` | one-time App Code | 60s |
| `${REDIS_KEY_PREFIX}spring:session` | OAuth handshake session namespace | 5m |

`RedisGateway`가 `app.redis.key-prefix`를 붙이므로 호출자는 logical key만 넘긴다.
Timeline task는 값 PSETEX와 전역·사용자별 index ZADD/ZREM(+사용자 index PEXPIRE)을 Lua 한 경계에서
수행하고, 목록 조회용 ZREVRANGE·후보 순서 정렬 MGET·batch ZREM primitive도 gateway가 제공한다
(Lua 안에서 task JSON은 decode하지 않는다). 서버간 처리 stage는 현재 task JSON 전체를 기대값으로
비교하는 Lua CAS로 전이하며, PROCESSING replacement는 index를 유지하고 terminal replacement는 index에서
제거한다.
logical key는 `{feature}:{entity}:{id}` namespace 형태로 만들고 feature store의 상수에서 조립한다.
호출부 key에 `dev_` 같은 environment prefix를 hardcode하지 않는다.
dev는 공유 Redis에서 `dev_` prefix를 쓰고 local/prod 기본값은 빈 문자열이다.
Spring Session은 framework-managed 영역이며 namespace 설정으로 격리한다.

과거 같은 날짜 작업 admission에 쓰던 `timeline:date-guard:*` key는 더 이상 application key 계약이
아니다. 배포 전에 남은 key는 읽거나 일괄 삭제하지 않으며 설정돼 있던 TTL로 자연 만료한다.

### S3

사진 object body를 저장하고 DB JSON payload에는 `filename`, client URI와 materialized CDN URL을 둔다.
full key는 DB column으로 저장하지 않는다. live 경로와 저장된 `photoUrl`은 subject 기반
`{hex(SHA-256(subjectId 16 bytes))}/photos/{filename}` 단일 규칙을 사용한다.
Event PATCH의 수동 PHOTO는 client가 업로드 완료 뒤 보내므로 서버가 object 존재를 조회하지 않는다.
해당 입력에는 `description`·`photoUrl`이 없고, 저장 시 `description=null`과 서버가 materialize한 CDN URL을
쓴다. 삭제된 PHOTO를 다시 추가할 때 Android는 새 presign 응답의 filename을 사용하고 과거 object key를
재사용하지 않는다. 이미 업로드를 마친 동일 pending addition의 PATCH 재시도만 그 pending filename을
보존할 수 있으며 서버는 pending delete key를 조회해 차단하지 않는다.

삭제는 두 경로다. draft cleanup은 단건 `DeleteObject`(전역 client 설정 그대로) 뒤 source row를 지운다.
Event/DailyRecord 삭제는 root/junction/non-PHOTO orphan hard delete와 함께 MySQL job을 만들고 유효한
orphan PHOTO Item을 보존한 뒤 즉시 성공하며, 별도 worker가
`DeleteObjects` 배치(최대 1,000 key/request, verbose, 요청 단위 apiCallTimeout 10s·
apiCallAttemptTimeout 3s)를 transaction 밖에서 호출한다. `Deleted`로 확인된 job과 그 PHOTO Item만
별도 transaction에서 지운다. 일일 실행은 한 요청만 보내므로 객체별 Error·응답 누락·SDK 예외와 1,000개
초과분은 두 행을 남겨 다음 날 실행에서 재시도·처리한다. PHOTO payload가 깨졌거나 filename/object key를
만들 수 없으면 job을 건너뛰고 손상 Item의 hard delete는 진행한다(orphan 허용).

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
- staging·final payload의 텍스트 값, AI 결과가 저장한 `timeline_events`의 `title`/`subtitle`/`question`,
  `user_memories.memory`는 v1 privacy 치환 후의 값이다(`clientPhotoUri`만 storage 원문 유지 — AI 전달
  에서만 치환). 사용자 편집(Event PATCH/memo PUT)의 title·subtitle·memo는 원문 저장이다.
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
