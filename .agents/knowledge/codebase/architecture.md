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
DailyRecord 선생성 + source staging(한 트랜잭션) + Redis PROCESSING
→ AI dispatch (POST /v1/timeline — taskId·callbackToken·dailyRecordId·offset window)
→ AI가 validation + final Event/Item/junction INSERT + accepted source DELETE를 direct-write commit
→ status-only callback → 서버는 Redis terminal 전이만 기록(멱등)
```

Event 편집은 별도 동기 흐름이다. `photosToAdd`가 없거나 빈 PATCH는 guard 없이 Event/memo transaction을
실행한다. non-empty PHOTO 추가는 orchestration service가 입력을 preflight하고 날짜 guard를 취득한 뒤,
별도 public transaction service가 소유권·DRAFT를 다시 확인하고 Event/memo + PHOTO Item/junction을 한 번에
commit한다. orchestrator는 transaction 반환(즉 commit) 뒤 guard를 compare-and-release해 DB transaction과
Redis lease 경계를 섞지 않는다.

response envelope는 `GlobalExceptionHandler`, transaction ID와 access log는
`TransactionIdFilter`가 담당한다.

## Invariants

- controller는 HTTP 경계를, service는 use case를, repository/store/adapter는 I/O를 소유한다.
- 여러 저장소를 아우르는 atomicity는 repository가 아니라 orchestration transaction에서 보장한다.
- documented pattern을 자동 강제 규칙처럼 과장하지 않는다.
- 공개 계약은 실제 `*Api`, DTO, handler와 contract test를 함께 확인한다.

## Known Gaps

- API chain의 JWT authentication filter와 principal-to-userId 전달이 아직 없다.
- 실 AI writer(Laimory-AI)의 draft direct-write 구현은 별도 저장소 진행분이다(서버 측 http dispatcher와
  Event PATCH 수동 PHOTO writer는 구현됨).
- schema migration framework가 없다.

## Update When

새 layer·feature boundary, architecture test, security chain, transaction ownership 또는 핵심 runtime flow가
바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
```
