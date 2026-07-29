# Testing

## Scope

Gradle test task, local infrastructure, CI와 image build가 실제로 검증하는 범위를 설명한다.

## Read When

구현 검증 범위를 정하거나 test tag/task, CI 또는 Docker build를 바꿀 때 읽는다.

## Authoritative Sources

- `build.gradle`
- `.github/workflows/ci.yml`, `.github/scripts/test-monitoring-deploy-contract.sh`
- `Dockerfile`
- tests의 `@Tag("integration")`, `@ActiveProfiles("docker")`

## Current Test Layers

| Layer | Command | Infrastructure |
|---|---|---|
| unit, slice, ArchUnit | `./gradlew test` | 없음 |
| compile + unit verification | `./gradlew build` | 없음 |
| integration | `./gradlew integrationTest` | local MySQL·Redis |

- `test`는 `integration` tag를 제외한다.
- `integrationTest`는 `integration` tag만 실행한다.
- integration tests는 `docker` profile로 실제 local MySQL·Redis에 연결한다.
- AI dispatcher 배선(`AiDispatcherWiringTest`)은 일반 `test`/CI 범위에서 검증하고, callback의 실제
  MySQL·Redis 계약은 `TimelineCallbackTokenIntegrationTest`(integration)가 검증한다.
- `dev`, `main` 대상 PR CI는 alert rule shell 배포·monitoring workflow 계약을 먼저 검사한 뒤
  `./gradlew build`를 실행한다.
- integration test는 CI에 포함되지 않는다.
- Docker image build는 `-x test`라 test 결과는 CI/local verification에 의존한다.
- repository에 별도 lint/format Gradle task는 없다.

## Suggested Verification

```bash
./gradlew test
docker compose up -d
./gradlew integrationTest
```

focused 예:

```bash
./gradlew test --tests 'com.laimory.server.common.logging.TransactionIdFilterTest'
./gradlew integrationTest --tests 'com.laimory.server.timeline.service.TimelineCallbackTokenIntegrationTest'
```

## Invariants

- `./gradlew build`가 integration test까지 실행한다고 설명하지 않는다.
- 새 test category는 Gradle task, CI scope와 이 문서를 함께 검토한다.

## Known Gaps

integration test는 CI에서 실행되지 않으며 image build 자체도 test를 실행하지 않는다.

## Update When

task dependency/exclusion, tag/profile, required infrastructure, CI command 또는 Docker build가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
./gradlew tasks --all
```
