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
- component dependency는 field injection 대신 constructor injection을 사용한다
  (일반적으로 `@RequiredArgsConstructor`와 `private final` field).
- leaf service는 대체로 하나의 repository/store/adapter 책임을 감싼다.
- 여러 domain 작업은 orchestrator가 leaf service를 합성한다.
- 이 형태 전체가 ArchUnit으로 강제되는 것은 아니다. 실제 강제되는 대표 규칙은 application code의
  Redis 직접 접근 금지이며 `RedisAccessArchTest`가 확인한다.
- `SystemController`는 `/status`에서 `DataSource`를 직접 probe하고,
  `AuthHandoffPageController`는 정적 HTML handoff adapter인 의도적 예외다.

저장 경계:

- MySQL은 JPA와 `ddl-auto=validate`를 사용한다. schema 변경은 애플리케이션이 수행하지 않는다.
- application-owned Redis 접근은 `RedisGateway`를 거친다.
- OAuth handshake chain만 Redis-backed HTTP session을 사용하고 일반 API chain은 stateless다.

timeline draft의 큰 흐름은 다음과 같다.

```text
DailyRecord 선생성 + source staging(한 트랜잭션) + Redis PROCESSING/INPUT_PENDING
→ AI dispatch (POST /v1/timeline — taskId·최초 task token)
→ AI가 최초 token으로 GET /s/api/{v}/timeline/drafts/{taskId}/input 호출
→ 새 result token hash + Redis RESULT_PENDING CAS, 응답 body로 result token 전달
→ AI가 result token으로 POST .../result 호출
→ 새 callback token hash + Redis CALLBACK_PENDING CAS 선점
→ Event/Item/junction INSERT + accepted source DELETE를 한 MySQL transaction으로 commit
→ 응답 body로 callback token 전달 → status-only callback → Redis terminal CAS + 완료 푸시
```

Event 편집은 별도 동기 흐름이다. `photosToAdd`가 없거나 빈 PATCH는 Event/memo transaction을 실행한다.
non-empty PHOTO 추가는 orchestration service가 입력을 preflight하고, 별도 public transaction service가
소유권·DRAFT를 다시 확인해 Event/memo + PHOTO Item/junction을 한 번에 commit한다. 두 경로 모두 날짜
단위 Redis admission 없이 자기 DB transaction 경계만 가진다.

Event/DailyRecord 삭제는 preflight 뒤 별도 transaction service가 orphan PHOTO delete-job insert·원문
PHOTO Item 보존과 기존 root/junction/non-PHOTO orphan hard delete를 한 commit으로 묶는다. Event-Item
연결 해제(PHOTO 전용 DELETE)는 같은 두 계층을 재사용하되 junction 한 줄만 지운다 — transaction 안에서
Item 행 `PESSIMISTIC_WRITE` 잠금과 junction current-read 잠금 조회로 target 존재·마지막 참조를 원자
판정하고, 마지막 참조 PHOTO는 같은 delete-job 규칙으로 넘긴다. 날짜 Redis admission은 없다. S3는
request transaction에 포함하지 않고 현재 REST 프로세스의 단일 scheduled worker가
MySQL job을 oldest-first로 읽어, 성공 job과 원문 PHOTO Item을 별도 transaction에서 함께 제거한다.

response envelope는 `GlobalExceptionHandler`, transaction ID와 access log는
`TransactionIdFilter`가 담당한다.

## Invariants

- controller는 HTTP 경계를, service는 use case를, repository/store/adapter는 I/O를 소유한다.
- 여러 저장소를 아우르는 atomicity는 repository가 아니라 orchestration transaction에서 보장한다.
- documented pattern을 자동 강제 규칙처럼 과장하지 않는다.
- 공개 계약은 실제 `*Api`, DTO, handler와 contract test를 함께 확인한다.

## Known Gaps

- API chain의 JWT authentication filter와 principal-to-userId 전달이 아직 없다.
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
