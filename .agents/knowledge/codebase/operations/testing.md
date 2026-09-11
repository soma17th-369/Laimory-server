# Testing

## Scope

Gradle test task, local infrastructure, CI와 image build가 실제로 검증하는 범위를 설명한다.

## Read When

구현 검증 범위를 정하거나 test tag/task, CI 또는 Docker build를 바꿀 때 읽는다.

## Authoritative Sources

- `build.gradle`
- `.github/scripts/test-flyway-migrations.sh`, `src/test/resources/db/legacy/pre-flyway-schema.sql`
- `.github/workflows/ci.yml`, `.github/scripts/test-monitoring-deploy-contract.sh`
- `Dockerfile`
- tests의 `@Tag("integration")`, `@ActiveProfiles("docker")`

## Current Test Layers

| Layer | Command | Infrastructure |
|---|---|---|
| unit, slice, ArchUnit | `./gradlew test` | 없음 |
| compile + unit verification | `./gradlew build` | 없음 |
| integration | `./gradlew integrationTest` | local MySQL·Redis |
| unit coverage | `./gradlew test jacocoTestReport` | 없음 |
| combined coverage | `./gradlew build integrationTest jacocoAllTestReport` | local MySQL·Redis |
| Flyway 편입·동시 실행 | `./gradlew bootJar` 후 `bash .github/scripts/test-flyway-migrations.sh` | 독립 Docker MySQL·Flyway CLI + 앱 JDBC |

- `test`는 `integration` tag를 제외한다.
- `integrationTest`는 `integration` tag만 실행한다.
- integration tests는 `docker` profile로 실제 local MySQL·Redis에 연결한다.
- CI의 빈 앱 DB는 운영과 동일한 공통 Flyway 활성화 설정으로 생성하고 JPA가 검증한다.
  별도 script는 V1 target을 고정해 기존 스키마 fixture와 DDL을 비교하고 baseline의 데이터 보존·재실행·checksum
  실패를 검증한다. 두 독립 프로세스의 최초 생성과, 임시 V2를 실행 중 native lock 대기가 겹친 뒤
  두 프로세스가 성공하고 이력/결과는 한 번만 기록되는 것도 검증한다. 임시 V2는 앱에 포함되지 않는다.
  script가 만든 컨테이너/네트워크만 제거하며 기존 local volume은 사용하지 않는다.
- AI dispatcher 배선(`AiDispatcherWiringTest`)은 일반 `test`/CI 범위에서 검증하고, 서버간 AI 흐름
  (dispatch→입력→결과→콜백)의 실제 MySQL·Redis 계약은 `TimelineAiTaskFlowIntegrationTest`(integration)가
  검증한다.
- Kakao 지오코딩의 자원·장애 경계(pool/pending/retry/circuit/lifecycle)는 별도 load task가 아니라
  일반 `test`의 결정적 loopback 테스트(`KakaoGeoResourceBoundaryTest` 등 — MockWebServer, 실 Kakao
  key·network 불필요)가 production 배선 그대로 검증한다. 통계적 부하/p95 게이트는 없다.
- `dev`, `main` 대상 PR CI는 alert rule shell 배포·monitoring workflow 계약을 먼저 검사한 뒤
  Compose의 MySQL·Redis healthcheck를 기다리고
  `./gradlew build integrationTest jacocoAllTestReport`를 실행한다.
- 앱 배포 pre-stop 계약은 `.github/scripts/test-deploy-contract.sh`, 관리자 SSM target 선택은
  `bash .github/scripts/test-admin-tunnel.sh`가 검증하며 둘 다 PR CI에서 실행한다.
- 관리자 connector/Host/Origin/CSRF/OpenAPI는 `AdminHttpTest`·`AdminDisabledHttpTest`의 실 Tomcat
  HTTP 테스트(인프라 없음), INSERT 불변성은 `TermPersistenceIntegrationTest`, Redis CSRF 세션과
  저장 후 `/intro` 반영은 `AdminPersistenceIntegrationTest`가 검증한다.
- JaCoCo는 `test`와 `integrationTest`를 각각 계측한다. `jacocoTestReport`는 unit HTML/XML을,
  `jacocoAllTestReport`는 두 실행 데이터를 합산한 HTML/XML을 만든다.
- PR CI는 JUnit test report와 합산 coverage report를 artifact로 남긴다. coverage 최소 비율은
  설정하지 않으며 보고서 수치 자체로 build를 실패시키지 않는다.
- Docker image build는 `-x test`라 test 결과는 CI/local verification에 의존한다.
- repository에 별도 lint/format Gradle task는 없다.

## Suggested Verification

```bash
./gradlew test jacocoTestReport
docker compose up -d --wait
./gradlew build integrationTest jacocoAllTestReport
```

focused 예:

```bash
./gradlew test --tests 'com.laimory.server.common.logging.TransactionIdFilterTest'
./gradlew integrationTest --tests 'com.laimory.server.timeline.service.TimelineAiTaskFlowIntegrationTest'
```

## Invariants

- `./gradlew build`가 integration test까지 실행한다고 설명하지 않는다.
- coverage report 생성 실패는 CI 실패지만 coverage 비율은 merge gate가 아니다.
- 새 test category는 Gradle task, CI scope와 이 문서를 함께 검토한다.

## Known Gaps

Docker image build 자체는 test를 실행하지 않는다.

## Update When

task dependency/exclusion, tag/profile, required infrastructure, CI command 또는 Docker build가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
./gradlew tasks --all
docker compose config --quiet
```
