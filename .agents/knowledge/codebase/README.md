# Codebase Knowledge Index

## Scope

Laimory 서버의 구조, 런타임 흐름, 외부·내부 계약, 저장소와 운영 지식을 변경 유형별로 연결한다.

## Read When

구현 범위를 잡거나 변경 후 문서 영향도를 확인할 때 읽는다.

## Router

| Page | Read when | Related paths | Update when | Authority | Validate with |
|---|---|---|---|---|---|
| [Overview](overview.md) | 저장소를 처음 탐색하거나 변경 범위를 잡을 때 | `build.gradle`, `src/main`, properties, Docker·workflow | 기술 스택·기능 경계·외부 시스템이 바뀔 때 | build/config/package tree | `./gradlew test` |
| [Architecture](architecture.md) | package·의존성·service·transaction 경계를 바꿀 때 | controllers, services, repositories, architecture tests | 구조 규칙이나 실제 예외가 바뀔 때 | 생성자 의존성·tests | `./gradlew test` |
| [Constraints](constraints.md) | 구현 대안을 선택하거나 데이터·인프라를 바꿀 때 | config, schema, workflow, enforced tests | 기술·운영 제약이 추가·해소될 때 | code/config/workflow | 관련 test·config validation |
| [Change impact](change-impact.md) | 변경 전 범위와 변경 후 동기화를 확인할 때 | 전체 repository | 연결 관계나 최소 검증이 바뀔 때 | 실제 의존·배포 경로 | `git diff` + 관련 검증 |
| [Timeline draft runtime](runtime/timeline-draft.md) | draft 생성·polling·callback·finalize·cleanup을 바꿀 때 | `timeline/**`, schema, Redis | 단계·보상·TTL·상태가 바뀔 때 | services/entities/tests | timeline unit/integration tests |
| [Authentication runtime](runtime/authentication.md) | OAuth·JWT·Security·userId 전파를 바꿀 때 | `auth/**`, `user/**`, security config | 인증 계약·enforcement·fallback이 바뀔 때 | security/auth code·tests | auth/security tests |
| [API interface](interfaces/api.md) | endpoint·DTO·envelope·error·OpenAPI를 바꿀 때 | `*Api.java`, controllers, DTOs, error handling | 공개 HTTP 계약이 바뀔 때 | API interfaces·tests·runtime OpenAPI | controller/contract tests |
| [AI contract](interfaces/ai-contract.md) | AI staging·dispatch·callback 계약을 바꿀 때 | dispatcher, staging, assembler, callback | write-then-notify 계약이나 인증이 바뀔 때 | code/schema/tests | fake unit/wiring + callback integration tests |
| [External integrations](interfaces/external-integrations.md) | OAuth provider·Kakao Maps·S3/CDN을 바꿀 때 | provider/storage code, properties | 외부 요청·retry·보안 계약이 바뀔 때 | adapter/config/tests | provider/storage tests |
| [Persistence](data/persistence.md) | Entity·schema·repository·Redis key를 바꿀 때 | entities, repositories, `schema.sql`, Redis stores | shape·key·TTL·migration 절차가 바뀔 때 | schema/entities/stores | unit + integration tests |
| [Local development](operations/local-development.md) | 로컬에서 앱과 의존성을 실행할 때 | Gradle, properties, Compose | 로컬 profile·명령·dependency가 바뀔 때 | config/Compose | `docker compose config --quiet` |
| [Testing](operations/testing.md) | 검증 범위·CI·integration test가 필요할 때 | Gradle tasks, test tags, CI | test task·tag·CI 범위가 바뀔 때 | build/tests/workflow | 해당 Gradle task |
| [Environments](operations/environments.md) | local/dev/prod 차이와 환경변수를 다룰 때 | properties, workflow, deploy runbook | profile·mode·주입 방식이 바뀔 때 | config/workflow/deploy | context boot·config search |
| [Deployment](operations/deployment.md) | 배포·preflight·bootstrap·복구를 바꿀 때 | deploy workflow, Dockerfile, `deploy/` | rollout·health·rollback·운영 절차가 바뀔 때 | workflow/deploy | workflow and asset tests |
| [Observability](operations/observability.md) | log·transaction ID·ELK·민감정보를 다룰 때 | logging code/config, ELK config | 추적 계약·로그 pipeline이 바뀔 때 | filter/logback/deploy config | logging tests·JSON validation |

## Maintenance

- 상세 문서의 반복 설명보다 이 표의 라우팅 정확성을 우선한다.
- 새 페이지는 서로 다른 작업에서 반복해 필요한 지식이 충분할 때만 만든다.
- 상세 목록을 생성할 수 있는 code generation은 현재 없으므로 generated 문서를 만들지 않는다.
