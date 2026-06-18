# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Laimory is the backend **web application server for an Android app**. It exposes a REST API consumed by the mobile client.

## 용어 사전 (Ubiquitous Language)

도메인 용어(엔티티명, 상태명, 필드명, 클래스/변수 네이밍 등)가 필요하면 반드시 [.agents/UbiquitousLanguage.md](.agents/UbiquitousLanguage.md)의 표현을 기준으로 사용한다. 이 문서가 도메인 용어와 **사용 금지 표현**의 단일 기준(single source of truth)이다.

- 새 코드를 작성하거나 도메인 개념을 명명할 때는 먼저 이 문서를 확인해 한글명↔영문명을 일치시킨다.
- 임의의 동의어를 만들지 말고, 문서의 "사용 금지 표현"에 있는 단어(예: `Candidate`, `Card Item`, `Map<String, Object> payload`)는 대체 표현으로 바꿔 쓴다.

## Tech Stack

- **Spring Boot 3.5.8** (Spring MVC, Spring Data JPA)
- **Java 21** (Gradle toolchain)
- **MySQL** (via `mysql-connector-j`)
- **Lombok** for boilerplate reduction
- **Gradle** build (`./gradlew`)

## Commands

```bash
./gradlew build          # compile + test
./gradlew test           # run tests
./gradlew bootRun        # run the server locally
```

Datasource is configured via environment variables: `DB_HOST`, `DB_USERNAME`, `DB_PASSWORD` (see `application.properties`).

## Architecture

Strict **3-layer architecture**: `Controller → Service → Repository`.

```
Controller   HTTP 진입점, 요청/응답 처리. Service만 호출.
Service      비즈니스 로직. Repository 또는 다른 Service에 접근.
Repository   Spring Data JPA. DB 접근 전담.
```

### 핵심 규칙: Service는 단 하나의 Repository에만 접근한다

각 Service는 **자신과 1:1로 대응하는 Repository 하나에만** 접근합니다. 여러 도메인에 걸친 기능은 Repository를 여러 개 주입하지 말고, **여러 Service를 조합**해서 구현합니다.

- ✅ `TimeLineService` → `TimeLineRepository` (only)
- ✅ `UserService` → `UserRepository` (only)
- ✅ 새 기능은 `TimeLineService` + `UserService`에 의존하는 별도 Service로 구현
- ❌ `TimeLineService`가 `TimeLineRepository`와 `UserRepository`를 둘 다 주입

즉, Service 간 협력은 **Service 합성**으로, 절대 **Repository 직접 접근**으로 풀지 않습니다.

### 패키지 구조 (feature 단위)

각 기능은 `com.laimory.server.<feature>` 하위 자체 패키지에 Controller / Service / Repository / Entity / DTO를 모두 포함한다.

- **작은 기능**: 한 패키지에 평평하게 둔다 (예: `appconfig/`).
- **큰 기능**: 레이어별 하위 패키지로 나눈다 (예: `timeline/`) — `controller/` · `service/` · `repository/` · `entity/` · `dto/`. 도메인 enum은 feature 루트에, 타입별 payload는 `payload/`에 둔다.

```
com.laimory.server
├── appconfig/                    # 작은 기능: 평평하게
│   ├── AppConfigController.java
│   ├── AppConfigService.java
│   ├── AppConfigRepository.java
│   ├── AppConfig.java            # JPA Entity
│   └── AppConfigResponse.java    # 응답 DTO
└── timeline/                     # 큰 기능: 레이어별 하위 패키지
    ├── entity/                   # JPA 엔티티
    ├── repository/               # Spring Data 레포 (+ Redis 스토어)
    ├── service/                  # 비즈니스 로직 (leaf 서비스 + 오케스트레이터)
    ├── controller/               # HTTP 진입점
    ├── dto/                      # 요청/응답 DTO
    └── payload/                  # sealed payload 타입
```

## Conventions

- **API 경로 prefix**: 호출 주체에 따라 prefix를 구분하고, 그 아래에 버전 `/api/v1`을 둔다.
  - **일반(공개) 요청**: `/api/v1/...` — 인증 없이 접근하는 공개 엔드포인트.
  - **서버간 통신**: `/s/api/v1/...` — 내부 서버↔서버 호출(예: AI 카드 생성 콜백). 공유 secret 헤더로 보호한다(`CallbackSecretInterceptor`가 `/s/**`를 검증).
  - **인증 필요 요청**: `/a/api/v1/...` — 사용자 인증이 필요한 엔드포인트(사용자 도입 시 사용).
- **의존성 주입**: 필드 주입 대신 `@RequiredArgsConstructor` + `private final` 생성자 주입.
- **응답 DTO**: Entity를 직접 반환하지 않고 Response DTO로 변환한다. 정적 팩토리 `from(Entity)` 패턴 사용 (`AppConfigResponse.from(config)` 참고).
- **DTO 네이밍**: API 경계에서 주고받는 DTO는 방향을 접미사로 드러낸다 — 요청 바디는 `...Request`, 응답으로 나가는 표현 DTO(중첩 포함)는 `...Response`로 끝낸다 (예: `CreateDraftTaskRequest`, `DraftTaskStatusResponse`, `TimelineCardResponse`). 서비스 계층 내부 전용이거나 요청 바디에 중첩되는 입력 요소 DTO는 방향 접미사 대신 도메인 이름을 쓴다 (예: `SourceItemDto`).
- **Controller 반환 타입**: `ResponseEntity<T>`.

## Git / Branch 전략

`main` / `dev` / 작업 브랜치(`feat`·`refactor`·`fix`) 3단계로 운영한다. 작업 브랜치는 `dev`에서 분기하고, PR을 통해 `dev` → `main` 순으로 머지한다. 상세 규칙은 [branch.md](branch.md) 참조.

## Database

- `spring.jpa.hibernate.ddl-auto=validate` — Hibernate가 스키마를 생성하지 않고 **검증만** 한다. 새 Entity를 추가하면 대상 DB에 테이블/컬럼이 먼저 존재해야 기동된다.
- **DDL은 수동 적용**: Flyway/Liquibase 미사용. Entity 추가/변경 시 대응하는 테이블 변경을 DB에 직접 반영해야 한다.
