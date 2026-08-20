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
  `available_at`=다음 claim eligibility, 성공 시 Item과 행 삭제)
- `timeline_draft_source_items` (API→AI 입력 staging, `(task_id, raw_id)` UNIQUE — payload는
  v1 privacy 치환 저장본이고 `clientPhotoUri`만 원문 유지, `cleanup_available_at`=retention cleanup
  eligibility)
- `users`, `refresh_tokens`, `account_erasure_jobs` (#305 — 탈퇴가 접수한 userId-only PENDING 삭제 작업)
- `user_subject_links` (인증 사용자↔콘텐츠 subject HMAC 매핑 — raw `user_id` 미저장)
- `user_memories` (subject당 1행 opaque JSON 문서, 행 존재=메모리 있음)
- `push_registrations`
- `subject_preferences → daily_notification_preferences` (#314·#318·#321 — subject 축 설정 버킷이
  담은 예정 알림 마스터와 일일 알림의 ON/OFF·occurrence 스케줄 상태)
- `term_documents → term_agreements` (버전별 불변 약관 문서와 회원 동의 이력 — #303)

`schema.sql`은 빈 Docker MySQL volume의 최초 초기화에 쓰인다.
`CREATE TABLE IF NOT EXISTS`라 기존 table을 변경하지 않으며 migration framework는 없다.
기존 dev/prod DB 변경은 애플리케이션 배포 전에 수동 DDL과 검증이 필요하다.
dev는 `dev` 브랜치 push가 자동 배포를 트리거하므로(`.github/workflows/deploy.yml` — 구 컨테이너
중단 후 새 컨테이너 기동), 스키마 변경 PR의 live DDL은 **머지 전에** dev DB에 적용해야 한다.
미적용 상태로 머지하면 새 앱이 `ddl-auto=validate` 기동 실패로 dev가 다운된다.
테이블 rename은 구 컨테이너를 즉시 깨뜨리므로(옛 이름 매핑이 table not found) 선적용이 아니라 구 컨테이너
중단~신 컨테이너 기동 사이에 실행해야 한다. `RENAME TABLE`은 자식 FK의 참조 테이블만 자동 승계하고
**제약 이름은 옛 이름 그대로 남기므로**, `schema.sql`의 제약 이름을 함께 바꿨다면 `ALTER TABLE ... DROP
FOREIGN KEY ... ADD CONSTRAINT ...` 한 문장을 따로 실행해야 신규 DB와 기존 DB가 같아진다(이 ALTER는
애플리케이션이 제약 이름을 읽지 않으므로 cutover 창 밖에서 실행해도 안전하다).

#314의 두 subject 축 테이블(`subject_preferences`·`daily_notification_preferences`)은 DDL만으로
끝나지 않는다. 가입 transaction이 신규 회원의 기본 행을 만들지만
기존 subject에는 backfill이 필요하고, DDL 선적용~컨테이너 교체 사이에 가입한 회원은 구버전 코드가
설정 행 없이 만든다. 그래서 rollout은 **두 단계 insert-if-absent backfill**이다 — ① 구 컨테이너가 도는
동안 테이블 생성 + 당시 모든 subject의 기본 행 삽입, ② 새 컨테이너 health 확인 뒤 같은 문장을 다시 실행해
공백 구간 가입자를 수렴시킨다. 구 image로 rollback한 뒤 재배포하면 그 구간이 새 공백이므로 두 단계를
다시 수행한다. 각 단계는 subject 수 대비 행 수와 anti-join 누락 0건으로 검증한다. **#318 이후 재실행하는
backfill은 일일 알림 행을 `enabled=TRUE`로 넣어야 한다** — #314 때 쓴 `FALSE` 문장을 그대로 재실행하면 그
구간 사용자만 일괄 발송에서 조용히 빠지고, 쓰기·조회 어느 쪽도 그 행을 고치지 않는다.
설정 조회와 쓰기 모두 행을 만들지 않으며 행이 없으면 던진다 — 기본값으로 가리면 "켜짐"이라 답하면서
아무것도 보내지 않는 상태가 되기 때문이다. worker 스캔도 없는 행을 발견하지 못하므로
backfill이 <b>유일한</b> 복구 권위다. 탈퇴 subject의 `user_subject_links`는 물리 삭제(#302) 전까지 남아
있어 backfill이 그 행까지 다시 만든다 — FID가 없어 발송은 없지만 #302 삭제 대상에 포함해야 한다.

#318(일일 리마인더 기본 ON 전환)은 스키마를 바꾸지 않지만 **기존 행 일괄 갱신**이 필요하다. #314
rollout으로 이미 만들어진 행은 `enabled=false`라 코드 기본값만 바꿔서는 켜지지 않는다. `enabled`만
켜면 안 된다 — 꺼져 있는 동안 worker가 claim하지 않아 과거로 굳은 `next_due_at`이 허용 지연(30분)
안쪽이면 켠 직후 tick이 예정에 없던 알림을 보낸다. 그래서 `next_due_at`을 같은 문장에서 다음 미래
occurrence로 재장전한다(dev 적용 후 prod 별도):

```sql
SET @kst_now = CONVERT_TZ(NOW(6), '+00:00', '+09:00');
SET @next_due = IF(TIME(@kst_now) < '21:00:00',
                   TIMESTAMP(DATE(@kst_now), '21:00:00'),
                   TIMESTAMP(DATE(@kst_now) + INTERVAL 1 DAY, '21:00:00'));
UPDATE daily_notification_preferences
   SET enabled = TRUE, next_due_at = @next_due, updated_at = @kst_now;
```

DB 호스트 시간대가 UTC이므로 "지금"은 `CONVERT_TZ`로 KST 벽시계를 만들어 쓴다(이 저장소의 `DATETIME`
공통 계약). 검증은 갱신 행 수와 `enabled=TRUE`·`next_due_at`이 전부 미래인지로 한다.

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
삭제·cleanup은 기존 JPA repository가 담당한다. `cleanup_available_at`은 INSERT에서 생략하고 DB의
`DEFAULT CURRENT_TIMESTAMP(6)`로 최초 eligibility를 채운다.

`timeline_photo_delete_jobs`는 object registry가 아닌 순수 작업 테이블이다. `timeline_item_id`와 full
`object_key`는 각각 UNIQUE이며, 기본 RESTRICT FK의 `timeline_item_id`가 보존 중인 원문 PHOTO Item을
가리킨다. native `INSERT IGNORE`가 Item/object 중복 enqueue를 원자적으로 no-op하며 timestamp를 직접
채운다. `status`는 `PENDING`/`PROCESSING` 두 값이고 default `PENDING`은 기존 행 backfill과 구 binary
INSERT 호환용이다. 신규 writer는 삭제 transaction과 경합한 Event PATCH가 먼저 수렴하도록 `available_at`을 다음
Seoul calendar day 00:00으로 명시한다(DB default current timestamp는 구 binary 호환용). worker는
checked-in default인 매일 03:00 `Asia/Seoul`(cron/zone 환경 override 가능)에
모든 process에서 발화한다. 각 bounded worker는 짧은 transaction으로
`available_at <= :eligibleAt` 행을 `(available_at, created_at, PK)` 순서로 최대 250개
`FOR UPDATE SKIP LOCKED` claim하고, 같은 transaction에서 `status=PROCESSING`과 `available_at`을 다음 calendar day 00:00
`Asia/Seoul`로 옮긴 뒤 commit한다. `eligibleAt`과 다음 시각은 같은 application Clock instant를 KST로
변환해 parameter로 바인딩하며 DB `NOW()`를 eligibility 비교에 쓰지 않는다. 그 뒤 현재 junction을
재확인해 다시 연결된 Item의 job을 취소하고 S3 대상에서 제외한다. transaction 밖에서 S3를 호출하고 성공
job을 먼저 지운 뒤 해당 Item을 같은 completion transaction에서 지운다. job 삭제가 0건이면 재연결 취소나
선행 completion일 수 있으므로 Item을 지우지 않고, batch 일부만 지워지면 전체 completion을 rollback한다.
명시적 실패·응답 누락·SDK 예외는 `PENDING`으로 되돌리고, crash 행은 `PROCESSING`으로 다음 일일 실행까지
남는다. 둘 다 `available_at` 만료 뒤 재claim되며 이미 다른 worker가 완료한 행은 정상적인 idempotent 수렴이다.
실행 시각에 애플리케이션이 내려가 있어도 catch-up하지 않으며, Item 삭제가 실패하면 job 삭제도
rollback된다. Event PATCH는 subject+filename의 full object key로 job을 locking read한다. `PENDING` 또는
만료된 `PROCESSING`이면 job을 취소하고 보존 Item의 PHOTO/rawId 일치를 확인해 같은 Item을 재연결한다.
유효한 `PROCESSING`이면 409 `-1019`로 거절한다. pre-S3 association 재검증은 다른 재연결 경로의 방어선으로
계속 유지한다. 별도 시도 횟수·backoff·token·error·완료 이력 column은 없다.

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

`subject_preferences`·`daily_notification_preferences`(#314·#321)는 푸시 수신 설정을 두 축으로 나눈다.
마스터는 subject PK 한 행(`push_enabled`, 기본 TRUE)이고, 일일 알림 설정도 subject PK 한 행이다.
**두 번째 일일 알림은 이 테이블에 담을 수 없다** — #321이 값이 하나뿐이던 `notification_type` 판별자를
걷어내면서 PK를 `subject_id` 단독으로 줄였고, 새 알림은 컬럼도 판별자도 아니라 새 테이블이 된다.
발송 시각은 컬럼이 아니라 애플리케이션 상수가 소유한다(`NOTIFICATION_TIME=21:00`, #318 이후 사용자
입력 경로가 없어 행마다 같은 값을 복제할 이유가 없다). 그래서 이 테이블에는 `TIME` 컬럼이 없다.
`next_due_at`(DATETIME(6))은 `Asia/Seoul` 벽시계 계약이고 항상 **현재 이후 첫 occurrence**다 —
하루 1회 캡을 두지 않아 껐다 켠 시점에 고정 시각이 아직 오늘 미래면 같은 날 다시 발송될 수
있다(사용자 행동이므로 허용, 설계 검토 2026-08-19 확정). 행 값의 권위는 애플리케이션이다 —
`enabled` 컬럼 DDL default는 `FALSE`지만 insert가 항상 `enabled`를 명시하며 그 기본값은 코드의
`DEFAULT_ENABLED=true`다(#318 — 전체 사용자 일괄 발송). worker는 `(enabled, next_due_at, subject_id)`
index로 due 행을 `FOR UPDATE SKIP LOCKED` claim하고, 같은 짧은 transaction에서 "현재 시각 이후 첫
occurrence" 전진을 UPDATE 한 문장으로 끝낸 뒤 commit한다(FCM 호출은 transaction 밖). 시각이 서버
고정이라 claim된 행 전부가 같은 전진 값을 가져 문장이 하나로 수렴한다.

설정 쓰기는 행을 만들지 않으며 ON/OFF 쓰기(시각 변경 쓰기는 #318에서 제거)는 `next_due_at`을 다음 미래
occurrence로 재장전한다. 시각이 상수라 그 값을 알아내려고 행을 읽지 않는다 — 쓰기는 UPDATE 한 문장이다.
전진 값은 언제나 Java가 KST로 계산해 파라미터로 넘긴다. 이 계산을 SQL에 맡기면 JDBC가 `LocalDateTime`
파라미터를 connection timezone으로 변환하므로 JVM zone이 결과에 새어 들어간다(UTC 통합 테스트가 실측으로
잡는다). 행 존재는 가입 transaction과 rollout backfill이 보장하며 부재·0행은 조용히 넘기지 않고
던진다(운영 신호 — 복구는 backfill 재실행). 일일 알림 행은 마스터를 `ON DELETE RESTRICT`로 참조해
그 정리를 빠뜨린 삭제가 조용히 통과하지 않는다.

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
`user_memories`·`subject_preferences`는 subject PK·FK RESTRICT다. subject FK는 `user_subject_links.subject_id`를
`ON DELETE RESTRICT`로 참조한다 — mapping 삭제가 콘텐츠를 암묵 cascade하지 않게 하며, 탈퇴는 콘텐츠
명시 삭제 후 mapping을 마지막에 지우는 계약이다. 이 owner 테이블들에는 raw `user_id` 컬럼이 없고
runtime repository/entity도 subject만 읽고 쓴다.

`timeline_events.question`은 `VARCHAR(255) NULL`이다(#252). AI 결과 저장 transaction만 쓰는 컬럼이라
편집 API 경로는 값을 건드리지 않으며, 기존 행은 backfill하지 않고 NULL로 남는다. entity는 length 지정
없는 `String`(Hibernate 기본 255)이라 nullable 컬럼을 앱 배포 전에 먼저 추가해야 `ddl-auto=validate`가
통과한다.

`timeline_events.event_type`은 `VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'`이다(#166). default는 기존 행
backfill과 컬럼을 생략하는 writer의 INSERT 호환용이다. entity는 `@Enumerated(STRING)`
`TimelineEventType`이며, 결과 저장 transaction은 allowlist literal만 INSERT한다(미지원 literal은 결과 저장
400 — 새 literal 활성화 순서는 "Server enum 배포 → AI writer 활성화").

`term_documents`(#303)는 버전별 불변 약관 문서다. 컬럼은 핵심 4개(`term_type`·`version`·`title`·
`effective_at`)와 감사 3개뿐이며 원문·enum 사본 컬럼이 없다(#320 — 원문은 `laimory.app` 게시 page가
소유하고 URL은 slug+version으로 계산하므로 `content`·`content_url` 둘 다 두지 않는다). 게시 행
UPDATE·삭제 API가 없고 개정은 새 행 INSERT이며, 현재 문서는 `effective_at <= now(KST)`의 종류별 최신
행으로 계산한다(별도 active flag 없음). `(term_type, version)`·`(term_type, effective_at)` UNIQUE만
가진다 — 후자의 leftmost prefix가 종류별 current selection을 지원해 별도 조회 index를 두지 않는다.
`version`은 exact-match 식별자라 컬럼 단위 `utf8mb4_bin`(raw_id·FID 선례),
`term_type`은 enum literal exact-match라 컬럼 단위 `ascii_bin`이다(subject_id 선례) — 테이블 기본
`_unicode_ci`면 소문자 오타 seed가 JPQL `IN`(enum literal)에 case-insensitive 매칭돼 `@Enumerated`
hydration을 500으로 깨뜨리지만, binary 비교면 불일치 행이 조회에서 빠지고 readiness가
not-ready(fail-open)로 경보한다. readiness의 raw catalog 조회는 `term_type` 한 컬럼만 native projection으로
읽어 미지 literal도 hydration 없이 관측한다.
`effective_at`은 KST 벽시계 `DATETIME(6)`+`LocalDateTime`이다(`Instant` 매핑 금지 — 저장소 공통 계약).
단계·필수 여부·화면 순서·원문 slug는 코드 `TermType` mapping이 단독 권위라 컬럼으로 복제하지 않는다.
enforcement/readiness/동의 버전 검증은 ID·종류·버전만 담은 summary projection을 조회한다 — LOGIN gate가
모든 `/a/api` 요청에서 도는 경로라 판정에 쓰지 않는 컬럼을 함께 적재하지 않는다.
운영 seed는 원문 page 게시 후 수동 INSERT다.

기존 live DB에서 #320으로 넘어갈 때는 새 Server를 먼저 배포하고(`ddl-auto=validate`는 매핑되지 않은
잔여 컬럼을 문제 삼지 않는다) 그다음 제거 DDL을 적용한다 — 반대 순서면 구 entity가 없는 컬럼을 찾는다.
제거 대상은 `idx_term_documents_stage_effective` index와 `content`·`stage`·`required`·`display_order`
컬럼이다. 네 컬럼이 NOT NULL·무기본값이라 배포와 DDL 사이에 INSERT가 있으면 실패하므로, 이 전환은 두
테이블이 0행인 pre-activation 창에서만 수행한다.

```sql
ALTER TABLE term_documents
    DROP INDEX idx_term_documents_stage_effective,
    DROP COLUMN content,
    DROP COLUMN stage,
    DROP COLUMN required,
    DROP COLUMN display_order;
```

`users`(#305)는 회원 상태 컬럼을 가진다 — `status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'`
(`ACTIVE`|`WITHDRAWAL_PENDING`)와 `withdrawal_requested_at DATETIME(6) NULL`. `provider_user_id`는
nullable이지만 `ACTIVE` 행은 application invariant로 non-null이며, NULL은 탈퇴 transaction의 identity
release뿐이다 — `(provider, provider_user_id)` UNIQUE는 유지되고 MySQL nullable UNIQUE가 여러 탈퇴
generation NULL을 허용하면서 같은 identity의 신규 ACTIVE 행은 하나로 제한한다. 탈퇴 전이·Kakao
nickname 갱신은 모두 `status` 조건부 UPDATE(영향 행 수 판정)이고 read-then-write entity 저장 경로는
없다(stale 저장의 탈퇴 부활 방지).

`account_erasure_jobs`(#305)는 탈퇴가 durable하게 접수한 계정 데이터 삭제 작업이다(#302 worker가
소비). `user_id`만 저장하고 `subject_id`를 같은 row/table에 두지 않는다 — DB만으로 raw identity와
content subject를 평문 join할 수 없다는 `user_subject_links` 보안 속성 유지(#302는 착수 시
`SubjectMappingService#getRequired`로 해석). `user_id` UNIQUE가 회원당 활성 job 하나를 강제하고 user
FK는 `ON DELETE RESTRICT`다(job이 남은 user 행 삭제 금지 — CASCADE 금지, 삭제 순서는 #302
finalization 소유). status는 #305에서 `PENDING` 단일 값이며 worker claim/stage/retry column은 #302의
additive migration으로 확장한다. 쓰기는 탈퇴 transaction에 합류하는 native `INSERT IGNORE`
(insert-if-absent)뿐이라 JPA auditing이 돌지 않고 감사 컬럼은 insert SQL이 직접 채운다(`modified_by`
NULL) — `created_at`이 접수 감사 시각이다. entity는 read model이다.
**운영 제약**: PENDING job이 하나라도 남아 있으면 previous HMAC key retire와 두 번째 rotation을
수행하지 않는다(탈퇴 회원 mapping은 lazy rekey 기회가 없음 — secret 갱신 전 PENDING count 확인이
runbook gate). backlog 관측 지표는 두지 않는다(경보 미부착 지표 금지 원칙) — gate 확인은
`(status, created_at)` index를 타는 수동 SELECT다. live dev/prod 반영은 앱 배포 전
수동 DDL(users ALTER + job CREATE)이 필요하다(`ddl-auto=validate`).

`term_agreements`(#303)는 회원 동의 이력이다. owner는 인증 회원 raw `user_id`(FK 없음 —
`refresh_tokens` 선례)이고 — #305는 탈퇴 시 동의 이력을 old userId에 그대로 남기며 신규 가입에
연결하지 않는다(보존 기간·삭제 방식은 #302/#303 확정 대상) —
`(user_id, term_document_id)` UNIQUE + `(user_id, accepted_at, term_agreement_id)` 이력 index를 가진다.
문서 FK는 `ON DELETE RESTRICT`다(동의가 남은 문서 삭제 금지). 쓰기는 repository의 native
`INSERT IGNORE`(insert-if-absent)뿐이라 JPA auditing이 돌지 않고 감사 컬럼은 insert SQL이 직접 채우며
(`modified_by` NULL), 재전송·동시 동일 batch가 unique 예외 없이 수렴하고 기존 `accepted_at`을 덮어쓰지
않는다. `accepted_at`은 서버가 batch당 한 번 캡처한 KST 벽시계다. entity는 조회·validate용 read model이다.
live dev/prod 반영은 앱 배포 전 수동 `CREATE TABLE`이 필요하다(`ddl-auto=validate`).

### Redis

application-owned access는 `RedisGateway`를 거친다.

| Logical key/namespace | Purpose | Lifetime |
|---|---|---|
| `timeline:draft-task:{taskId}` | draft state (세 상태 모두 owner UUIDv4 subject·선생성 `dailyRecordId`·단계별 token hash 셋 보존, PROCESSING에만 `timelineWindow`·필수 `processingStartedAt` 포함). FAILED `error`는 JSON number이며 문자열 코드와 필수 필드가 빠진 shape는 역직렬화를 거부한다. null·미지 numeric error는 polling에서 `-1011`로 수렴한다. PROCESSING 만료는 key 소멸이며 FAILED 전이가 아니다. | PROCESSING 3m(입력 조회·결과 저장 성공마다 재확보), terminal 24h |
| `timeline:draft-task:processing-index` | stuck PROCESSING 관측용 sorted set(member=taskId, score=processingStartedAt epoch ms). task JSON 저장 뒤 native ZADD, terminal 저장 뒤 native ZREM하며 실패·응답 유실은 최신 task JSON 기준 멱등 보정한다. gauge read 때 PROCESSING TTL 밖 member를 정리한다. task key가 권위이고 index는 상태 판정에 쓰지 않는다. | key TTL 없음; member는 terminal 전이 또는 3m cutoff 관측 때 제거 |
| `timeline:draft-task:user:{canonicalUuid(subjectId)}:processing` | subject별 진행 작업 조회 index sorted set(member=taskId, score=processingStartedAt epoch ms). PROCESSING task 저장 뒤 native ZADD+PEXPIRE, terminal 저장 뒤 native ZREM하며 명령 실패·응답 유실과 PEXPIRE=false는 최신 task JSON 기준 멱등 보정한다. task JSON status/owner가 권위 — 목록 조회는 후보마다 JSON을 검증해 만료·terminal·타인 소유 member를 제외하고 best-effort ZREM한다(역직렬화 불가 JSON은 500·자동 삭제 금지). | key TTL 3m — 새 PROCESSING 저장마다 갱신(마지막 생성 뒤 inactivity cleanup이지 member별 TTL 아님); member는 terminal 전이·목록 조회 lazy prune 때 제거 |
| `timeline:user-memory-update:pending` | 미반영 날짜 sorted set(member=`canonicalUuid(subjectId):dailyRecordId`, score=최초 대기 epoch ms) | 기본 30d retention/TTL |
| `timeline:user-memory-update:user:{canonicalUuid(subjectId)}` | subject별 갱신 guard(`SET NX`) | PROCESSING 3m |
| `timeline:user-memory-update:{taskId}` | User Memory 작업 JSON(owner UUIDv4 subject, 대상 record IDs, base digest) | PROCESSING 3m |
| `auth:app-code:{sha256hex}` | one-time App Code | 60s |
| `${REDIS_KEY_PREFIX}spring:session` | OAuth handshake session namespace | 5m |

`RedisGateway`가 `app.redis.key-prefix`를 붙이므로 호출자는 logical key만 넘긴다.
Timeline task 최초 저장은 native SET PX, 서버간 처리 stage·terminal 전이는 현재 task JSON 전체를
기대값으로 비교하는 단일-key Lua CAS로 수행한다. 이 CAS는 missing key에서 실패해 만료 task를
부활시키지 않는다. task write가 성공한 뒤 전역·사용자별 index ZADD/ZREM(+사용자 index PEXPIRE)을
native 명령으로 각각 실행하고, 실패·응답 유실은 최신 task JSON을 읽어 해당 index만 멱등 보정한다.
목록 조회용 ZREVRANGE·후보 순서 정렬 MGET·batch ZREM primitive도 gateway가 제공한다.
logical key는 `{feature}:{entity}:{id}` namespace 형태로 만들고 feature store의 상수에서 조립한다.
호출부 key에 `dev_` 같은 environment prefix를 hardcode하지 않는다.
dev는 공유 Redis에서 `dev_` prefix를 쓰고 local/prod 기본값은 빈 문자열이다.
Spring Session은 framework-managed 영역이며 namespace 설정으로 격리한다.

과거 같은 날짜 작업 admission에 쓰던 `timeline:date-guard:*` key는 더 이상 application key 계약이
아니다. 배포 전에 남은 key는 읽거나 일괄 삭제하지 않으며 설정돼 있던 TTL로 자연 만료한다.
`RedisGateway`에는 keyspace `SCAN` primitive가 없으므로 namespace 전체 점검·삭제가 불가피하면
대상과 건수를 확인한 별도 승인 수동 `redis-cli` 작업으로 수행한다.

### S3

사진 object body를 저장하고 DB JSON payload에는 `filename`, client URI와 materialized CDN URL을 둔다.
full key는 DB column으로 저장하지 않는다. live 경로와 저장된 `photoUrl`은 subject 기반
`{hex(SHA-256(subjectId 16 bytes))}/photos/{filename}` 단일 규칙을 사용한다.
Event PATCH의 수동 PHOTO는 client가 업로드 완료 뒤 보내므로 서버가 object 존재를 조회하지 않는다.
해당 입력에는 `description`·`photoUrl`이 없고, 저장 시 `description=null`과 서버가 materialize한 CDN URL을
쓴다. 삭제된 PHOTO를 다시 추가할 때 Android는 새 presign 응답의 filename을 사용하고 과거 object key를
재사용하지 않는다. 이미 업로드를 마친 동일 pending addition의 PATCH 재시도만 그 pending filename을
보존할 수 있다. 서버는 pending delete key를 조회해 대기 job은 취소·재연결하고 처리 중이면 409로 거절한다.

삭제는 두 경로다. draft cleanup은 만료·eligible source row를 250개 단위 `SKIP LOCKED`로 claim하고
PHOTO full key를 `DeleteObjects` batch로 지운 뒤 성공 PHOTO와 S3가 필요 없는 non-PHOTO를 DB bulk
delete한다. PHOTO payload/filename이 깨졌으면 기존 정책대로 S3 orphan을 허용하고 source row는 지운다.
명시적 S3 실패·응답 누락·SDK 예외 PHOTO row는 다음 일일 실행까지 남는다.
Event/DailyRecord 삭제는 root/junction/non-PHOTO orphan hard delete와 함께 MySQL job을 만들고 유효한
orphan PHOTO Item을 보존한 뒤 즉시 성공하며, 별도 worker가
`DeleteObjects` 배치(최대 1,000 key/request, verbose, 요청 단위 apiCallTimeout 10s·
apiCallAttemptTimeout 3s)를 transaction 밖에서 호출한다. worker는 S3 직전 현재 association을 재확인해
linked Item job을 취소하며, `Deleted`로 확인된 orphan job과 그 PHOTO Item만 별도 transaction에서 지운다.
process당 기본 concurrency 1, batch 250, 최대 4 batch/60초로 유계이고
여러 process가 같은 claim protocol에 참여한다. 객체별 Error·응답 누락·SDK 예외는 두 행을 남겨 다음 날
실행에서 재시도한다. PHOTO payload가 깨졌거나 filename/object key를
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
  보존한다(다른 Event에도 연결된 shared Item·PHOTO는 유지). S3 작업 권위는 MySQL job row이며 worker는
  S3 호출 직전 현재 association을 재확인해 linked Item의 job을 취소한다. orphan job만 S3 처리하고 성공 시
  Item과 row를 한 transaction에서 삭제하며 실패 시 둘 다 보존한다.

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
