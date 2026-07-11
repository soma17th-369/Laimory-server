# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Laimory is the backend **web application server for an Android app**. It exposes a REST API consumed by the mobile client.

## 작업 원칙

- **불확실하면 멈추고 묻는다.** 해석이 여럿이면 임의로 하나 고르지 말고 제시한다. 더 단순한 방법이 있으면 말한다.
- **YAGNI — 요청한 것만 구현한다.** 투기적 추상화, 단일 사용처를 위한 일반화, 미요청 유연성·설정성, 불가능한 케이스 방어는 넣지 않는다.
- **수술적 변경.** 요청과 무관한 코드·주석·포맷은 건드리지 않고 기존 스타일을 따른다. 내 변경이 만든 orphan(미사용 import·변수 등)만 정리하고, 기존 dead code는 삭제하지 말고 언급만 한다.
- **검증 가능한 목표로 바꿔 실행한다.** "버그 수정"→"재현 테스트를 먼저 작성해 통과시킨다", "리팩터"→"변경 전후 테스트 통과 확인".

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

- **API 경로 prefix**: 호출 주체에 따라 prefix를 구분하고, 그 아래에 버전 세그먼트를 둔다.
  - **일반(공개) 요청**: `/api/{applicationVersion}/...` — 인증 없이 접근하는 공개 엔드포인트.
  - **서버간 통신**: `/s/api/{applicationVersion}/...` — 내부 서버↔서버 호출(예: AI 카드 생성 콜백). 각 엔드포인트가 자체 인증한다(블랭킷 인터셉터 없음). AI 콜백은 **task별 one-time Callback-Token** 헤더로 검증한다(원문 토큰은 발급 시 AI에만 전달, Redis엔 SHA-256 해시만 보관, 상수시간 비교).
  - **인증 필요 요청**: `/a/api/{applicationVersion}/...` — 사용자 인증이 필요한 엔드포인트(사용자 도입 시 사용).
  - prefix 상수는 `com.laimory.server.common.ApiUrls`(`API_URL`/`SERVER_API_URL`/`AUTHENTICATED_API_URL`)에 모으고, 컨트롤러는 **클래스 레벨 `@RequestMapping(ApiUrls.…)`**으로 base 경로를 선언한다. prefix가 다른 엔드포인트(예: 공개 vs 서버간 콜백)는 별도 컨트롤러로 나눈다.
- **API 버저닝**: 버전은 하드코딩하지 않고 **정규식으로 제약한 path variable**로 받는다 (예: `/api/{applicationVersion:v\d+}/...`). 컨트롤러는 이 값을 `@PathVariable String applicationVersion`으로 받아 **그대로 Service에 전달**하고, **버전별 동작 분기는 Service 계층에서 해결**한다. 컨트롤러에는 버전 해석 로직을 두지 않는다. (정규식 패턴은 `ApiUrls.VERSION` 한 곳에 모아 둔다.)
- **의존성 주입**: 필드 주입 대신 `@RequiredArgsConstructor` + `private final` 생성자 주입.
- **응답 DTO**: Entity를 직접 반환하지 않고 Response DTO로 변환한다. 정적 팩토리 `from(Entity)` 패턴 사용 (`AppConfigResponse.from(config)` 참고).
- **DTO 네이밍**: API 경계에서 주고받는 DTO는 방향을 접미사로 드러낸다 — 요청 바디는 `...Request`, 응답으로 나가는 표현 DTO(중첩 포함)는 `...Response`로 끝낸다 (예: `CreateDraftTaskRequest`, `DraftTaskStatusResponse`, `TimelineCardResponse`). 서비스 계층 내부 전용이거나 요청 바디에 중첩되는 입력 요소 DTO는 방향 접미사 대신 도메인 이름을 쓴다 (예: `SourceItemDto`).
- **Controller 반환 타입**: `ResponseEntity<T>`.

## 에러 처리 / 응답 컨벤션

앱-facing API의 모든 응답(성공·에러)은 `ApiResponse{header{code,message}, body}` envelope로 나간다. 요청 추적 ID는 envelope가 아니라 응답 HTTP 헤더 `Transaction-Id`로 나간다.
성공은 `COMMON_0000` + body, 에러는 `ERROR_*` + `body=null` — 클라이언트는 **"code가 `ERROR_`로 시작하면 에러"**로 분기한다.
에러→envelope 변환은 전역 `common.error.GlobalExceptionHandler`(`ResponseEntityExceptionHandler` 상속)가 전담한다.

### 에러 throw 규칙

- **서비스는 try/catch로 응답을 만들지 않는다 — 던지기만 한다.** HTTP status·응답 shape·메시지 로캘은 전부 전역 핸들러가 결정한다.
- **도메인 에러** → `throw new BusinessException(ErrorCode.ERROR_xxxx)`.
  판별 기준: **서버 상태가 거부**했고(같은 요청이 다른 시점엔 성공 가능), **클라이언트가 코드를 보고 다르게 행동**해야 하는 에러 (예: task 만료 404, 토큰 불일치 401, 이미 SAVED 409).
- **입력 검증 실패** → `throw new IllegalArgumentException("설명")`.
  판별 기준: 요청 자체가 불량이라 **어떤 상태에서도 영원히 실패**하는 경우(필수값 누락, 미지원 값). 전역에서 400 `ERROR_0400`(제네릭 메시지)으로 매핑된다. 예외 메시지는 로그에만 남고 클라이언트엔 노출되지 않는다.
  - 나중에 클라이언트 분기/사용자 노출이 필요해지면 `BusinessException` + 전용 코드로 승격한다(승격 비용: enum 한 줄 + 번들 세 줄).
- **`ResponseStatusException` 사용 금지** — 서비스가 HTTP를 직접 알게 되는 레이어 오염. (핸들러의 브리지는 라이브러리 방어용 안전망일 뿐.)
- **내부 불변식 위반**(`IllegalStateException` 등)은 잡지 말고 전파한다 — catch-all이 500 `ERROR_0500`으로 처리하고 유일하게 stacktrace를 남긴다.
- `BusinessException`의 args(메시지 `{0}` 파라미터)에는 **서버 생성 값만**(taskId 등) 넣는다. 사용자 입력 금지(응답·로그 유출 방지).

### ErrorCode 규칙

- 에러 코드는 전부 `ERROR_` prefix. `COMMON_0000`은 성공 전용(enum에 없음, `ApiResponse.success` 소유).
- **블록 레지스트리**(`common.error.ErrorCode` 상단 주석이 SSOT): `ERROR_0xxx`=교차/폴백 전용(뒤 세 자리=HTTP 힌트, 도메인 사용 금지), `ERROR_1xxx`=timeline, 새 도메인은 1000 블록 단위로 할당. 도메인 블록 숫자는 HTTP status와 무관하다(status는 enum 필드가 SSOT).
- **코드명은 공개 API 계약** — 한번 배포되면 클라이언트가 분기하므로 rename 금지.
- 새 코드 추가 시 `messages.properties`(기본=한국어)·`messages_ko`·`messages_en` 세 곳에 코드명 key로 메시지를 추가한다(전부 UTF-8). 누락하면 `ErrorCodeMessagesTest`가 빌드에서 실패시킨다.
- `messages*.properties` 문구는 envelope `header.message`로 **클라이언트에 그대로 노출되는 사용자-facing 메시지**다 — 짧은 사용자 문구로만 쓰고 내부 진단·운영 지침을 넣지 않는다. 클라이언트 분기는 message가 아니라 code로 한다.

### transactionId / 로깅

- 모든 요청에 UUIDv7 transactionId가 부여된다(`common.logging.TransactionIdFilter`가 요청마다 새로 발급, 클라이언트 제공 값 재사용 없음). 클라이언트 노출 채널은 응답 HTTP 헤더 `Transaction-Id` **하나뿐**(envelope body에는 넣지 않는다). 로그엔 MDC로 자동 포함되므로 **코드에서 tx를 수동으로 로그에 넣지 않는다**.
- access 로그는 필터가 요청당 1줄 남긴다(5xx ERROR / 4xx WARN / 정상 INFO). **핸들러 밖에서 예외를 또 로깅하지 않는다**(이중 로깅 금지).
- 민감정보(토큰·presigned URL·query string·본문)는 로그 금지. path는 `getRequestURI()`(query 제외)만.

## Git / Branch / Commit 전략

`main` / `dev` / 작업 브랜치(`feat`·`refactor`·`fix`) 3단계로 운영한다. 작업 브랜치는 `dev`에서 분기하고, PR을 통해 `dev` → `main` 순으로 머지한다. 상세 규칙은 [.agents/branch.md](.agents/branch.md) 참조.

커밋 메시지는 `<type>: <간단한 작업 내용>` 형식의 Commit Type 컨벤션을 따른다. 사용 가능한 type 목록과 규칙은 [.agents/commit.md](.agents/commit.md) 참조.

### 작업 전 이슈 등록

단일 파일 수정·간단 버그픽스를 넘어서는 **일정 규모 이상의 작업**(새 컴포넌트·의존성 추가, 여러 파일에 걸친 변경, 리팩터)은 착수 전 **`create-issue` 스킬로 GitHub 이슈를 먼저 등록**하고, 작업 브랜치를 그 이슈에 연결(PR 본문에 `Closes #N`)해 진행한다. 지금 바로 코드 한 줄만 고치는 trivial 작업은 제외한다.

## Redis 키 컨벤션

- **모든 Redis 접근은 `com.laimory.server.common.redis.PrefixedRedis` facade를 통한다.** `StringRedisTemplate`/`RedisTemplate` 등 `org.springframework.data.redis..` 타입의 직접 주입·사용은 금지하며, `RedisAccessArchTest`(ArchUnit)가 빌드에서 강제한다(위반 시 `./gradlew test` 실패).
- **논리 키**는 콜론 네임스페이스 `{feature}:{entity}:{id}` 형태로 쓰고(예: `timeline:draft-task:{taskId}`), feature별 `KEY_PREFIX` 상수로 키 조립을 한 곳에 모은다.
- **환경 prefix**(dev/prod 단일 Redis 공유 시 격리용)는 `app.redis.key-prefix`(env `REDIS_KEY_PREFIX`)로 주입하며 기본값은 빈 문자열(prod·로컬). dev는 [deploy.yml](.github/workflows/deploy.yml)의 `-e REDIS_KEY_PREFIX=dev_`로 고정한다. prefix 부착은 `PrefixedRedis`가 전담하므로 호출부는 항상 **논리 키만** 넘기고, 코드에 prefix 값을 하드코딩하지 않는다.

## Database

- `spring.jpa.hibernate.ddl-auto=validate` — Hibernate가 스키마를 생성하지 않고 **검증만** 한다. 새 Entity를 추가하면 대상 DB에 테이블/컬럼이 먼저 존재해야 기동된다.
- **DDL은 수동 적용**: Flyway/Liquibase 미사용. Entity 추가/변경 시 대응하는 테이블 변경을 DB에 직접 반영해야 한다.
