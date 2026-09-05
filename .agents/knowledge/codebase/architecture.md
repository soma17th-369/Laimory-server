# Architecture

## Scope

Laimory 서버의 package, HTTP 경계, service 합성, 저장소와 transaction 구조를 설명한다.

## Read When

새 feature를 배치하거나 controller/service/repository 의존성과 transaction 경계를 바꿀 때 읽는다.

## Authoritative Sources

- 실제 `src/main/java/com/laimory/server/**` 생성자 의존성과 package tree
- `src/test/java/**` architecture tests
- `SecurityConfig`, `OAuth2LoginSecurityConfig`
- timeline orchestration·leaf services와 repositories

## Current Implementation

구조는 strict 3-layer가 아니라 feature-first package 안에 layer subpackage를 두는 혼합형이다.
일반적인 HTTP 흐름은 다음과 같다.

```text
*Api interface → Controller → orchestration/leaf Service → Repository, Store, Adapter
```

- `*Api` interface가 OpenAPI annotation과 HTTP signature를 소유하고 controller가 구현한다.
- timeline/push/initializer/onboarding의 인증 사용자 API는 `@CurrentSubject UUID subjectId` parameter를
  쓰고 MVC argument resolver가 SecurityContext의 raw `Long` principal을
  `SubjectMappingService.getRequired`로 변환한다.
- component dependency는 field injection 대신 constructor injection을 사용한다
  (일반적으로 `@RequiredArgsConstructor`와 `private final` field).
- leaf service는 대체로 하나의 repository/store/adapter 책임을 감싼다.
- 여러 domain 작업은 orchestrator가 leaf service를 합성한다.
- 다른 feature의 상태를 읽거나 쓰는 orchestrator는 그 feature의 repository가 아니라 leaf service를
  통한다 — 예: `initializer`/`onboarding`은 `subject_preferences`를 소유한 `SubjectPreferenceService`만
  의존하고, 값이 어느 package에 저장되는지는 그 leaf service 뒤에 남는다(#382).
- 이 형태 전체가 ArchUnit으로 강제되는 것은 아니다. 실제 강제되는 규칙은 셋이다 — application code의
  Redis 직접 접근 금지(`RedisAccessArchTest`, 승인 예외는 `CacheConfig` 하나), subject mapping
  내부(repository·lookup key deriver)를 `SubjectMappingService` 외에는 의존 금지
  (`SubjectMappingAccessArchTest`, #282), ACTIVE 검사 캐시(`RedisActiveStatusCache`)를 filter 배선과
  탈퇴 evict 외에는 의존 금지(`AuthContextCacheAccessArchTest`, #429 — 발급·회전은 DB 직행 유지).
  subject mapping 캐시에는 대응 규칙이 없다. 우회해야 하는 호출자가 없어 `SubjectMappingService`에
  직접 달았기 때문이다.
- `SystemController`는 `/status`에서 `DataSource`를 직접 probe하고,
  `AuthHandoffPageController`는 정적 HTML handoff adapter인 의도적 예외다.

저장 경계:

- MySQL은 JPA와 `ddl-auto=validate`를 사용한다. schema 변경은 애플리케이션이 수행하지 않는다.
- application-owned Redis 접근은 `RedisGateway`를 거친다(승인 예외: `CacheConfig`의 Spring Cache
  Redis manager — 같은 key prefix를 붙인다).
- 캐시 배선은 `CacheConfig`가 소유한다. 무효화가 다른 인스턴스로 전파돼야 하면 Redis manager,
  아니면 Caffeine manager(`@Primary`)이며 어노테이션마다 `cacheManager`를 명시한다.
- OAuth handshake chain만 Redis-backed HTTP session을 사용하고 일반 API chain은 stateless다.

timeline draft의 큰 흐름은 다음과 같다.

```text
DailyRecord 선생성 + source staging(한 트랜잭션) + Redis PROCESSING/INPUT_PENDING
→ AI dispatch (POST /v1/timeline — taskId·최초 task token)
→ AI가 최초 token으로 GET /s/api/{v}/timeline/drafts/{taskId}/input 호출
→ 새 result token hash + Redis RESULT_PENDING native write(SET XX KEEPTTL), 응답 body로 result token 전달
→ AI가 result token으로 POST .../result 호출
→ retry receipt에 claimedAt 선점 write(token 불변)
→ Event/Item/junction INSERT + accepted source DELETE를 한 MySQL transaction으로 commit
→ commit 뒤 새 callback token hash + Redis CALLBACK_PENDING native write
→ 응답 body로 callback token 전달 → status-only callback → Redis terminal native write(SET XX PX 24h) + 완료 푸시
```

Event 편집은 별도 동기 흐름이다. `photosToAdd`가 없거나 빈 PATCH는 Event/memo transaction을 실행한다.
non-empty PHOTO 추가는 orchestration service가 입력을 preflight하고, 별도 public transaction service가
소유권·DRAFT를 다시 확인해 Event/memo + PHOTO Item/junction을 한 번에 commit한다. 두 경로 모두 날짜
단위 Redis admission 없이 자기 DB transaction 경계만 가진다.

하루 감정 수정(#325)은 저장과 같은 2계층 경계다 — 비트랜잭션 오케스트레이터
(`DailyRecordEmotionUpdateService`)가 날짜 사전 조회로 404·DRAFT 409를 거르고 ID snapshot만 별도
`@Transactional` writer(`DailyRecordEmotionUpdateTransactionService`)에 넘긴다. writer는 트랜잭션의
첫 DB 작업으로 SAVED 조건부 UPDATE를 실행하고 0행일 때만 재조회로 실패를 분류한다 — 사전 조회를
트랜잭션 밖에 두는 이유는 MySQL `REPEATABLE READ`에서 첫 조회가 snapshot을 고정해 실패 재조회가
동시 삭제 전 행을 다시 볼 수 있기 때문이다(`TimelineSaveService` → `TimelineSaveTransactionService`와
같은 형태). 수동 Event 생성(#326/#361)은 `TimelineEventCreateService`의 public `@Transactional`
메서드 하나가 소유 record 재확인·입력 검증·Event insert·optional PHOTO Item/junction 추가를 소유한다.
Event 상세 필드 공통 규칙은 package-private `TimelineEventInputRules`, 수동 PHOTO 검증·분류·저장 규칙은
package-private Spring bean `TimelineEventPhotoAddService`가 소유해 PATCH/생성이 공유한다. 사진의
DB-dependent `resolve`/`link`는 `MANDATORY`로 호출자 transaction 합류를 강제하고 트랜잭션 안에서 S3를
호출하지 않는다.

Event/DailyRecord 삭제는 preflight 뒤 별도 transaction service가 orphan PHOTO delete-job insert·원문
PHOTO Item 보존과 기존 root/junction/non-PHOTO orphan hard delete를 한 commit으로 묶는다. Event-Item
연결 해제(PHOTO 전용 DELETE)는 같은 두 계층을 재사용하되 junction 한 줄만 직접 DELETE로 지운다 —
영향 행 수 0(같은 junction 동시 해제의 후발)은 404로 수렴하고, 마지막 참조 판정은 best-effort 일반
읽기라 경합 시 job 없는 orphan Item이 남을 수 있다(일일 orphan 스위퍼가 수렴 담당). 마지막 참조
PHOTO는 같은 delete-job 규칙으로 넘긴다. 날짜 Redis admission은 없다. S3는
request transaction에 포함하지 않는다. 모든 REST 프로세스의 bounded worker가 eligible MySQL job을
`FOR UPDATE SKIP LOCKED`로 나눠 `PROCESSING` claim하고, 성공 job과 원문 PHOTO Item을 별도 transaction에서
함께 제거한다. Event PATCH는 같은 object key의 `PENDING` job을 취소해 보존 Item을 재연결하고,
`PROCESSING`이면 409로 거절한다.

orphan 스위퍼도 같은 2계층이다 — `TimelineOrphanItemSweeper`(스케줄 trigger, run 예산·요약 로그)와
`TimelineOrphanItemSweepService`(batch 하나의 `@Transactional` 경계)로 나뉜다. batch에 외부 I/O가 없어
별도 worker executor를 두지 않고, process 간 분배는 후보 PK를 좁게 잠그는
`FOR UPDATE SKIP LOCKED` claim이 담당한다. S3 삭제는 이 경로에 없다 — 스위퍼는 delete job을 만들 뿐이고
객체 삭제는 기존 worker가 그대로 수행한다.

response envelope는 `GlobalExceptionHandler`, transaction ID와 access log는
`TransactionIdFilter`가 담당한다.

## Invariants

- controller는 HTTP 경계를, service는 use case를, repository/store/adapter는 I/O를 소유한다.
- 여러 저장소를 아우르는 atomicity는 repository가 아니라 orchestration transaction에서 보장한다.
- documented pattern을 자동 강제 규칙처럼 과장하지 않는다.
- 공개 계약은 실제 `*Api`, DTO, handler와 contract test를 함께 확인한다.

## Known Gaps

- 실 AI(Laimory-AI)의 서버간 입력·결과 호출 구현은 별도 저장소 진행분이다(서버 측 http dispatcher와
  입력·결과 endpoint는 구현됨).
- schema migration framework가 없다.
- 같은 날짜 draft·수동 PHOTO 추가·삭제의 교차 작업 concurrency control은 미구현이다.

## Update When

새 layer·feature boundary, architecture test, security chain, transaction ownership 또는 핵심 runtime flow가
바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
```
