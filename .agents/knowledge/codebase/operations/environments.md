# Environments

## Scope

local, integration, dev와 prod의 profile, dependency, feature mode, logging과 automation 차이를 설명한다.

## Read When

환경별 property, mode, environment variable, logging, Redis isolation, monitoring topology 또는 deploy
automation을 바꿀 때 읽는다.

## Authoritative Sources

- `application.properties`, `application-docker.properties`
- `logback-spring.xml`, `docker-compose.yml`
- `.github/workflows/deploy.yml`
- `deploy/monitoring/*`
- live AWS, GitHub repository Variables와 host `.env`

## Current Matrix

| Environment | Spring profile | DB/Redis | AI | Geo | Push | Subject key | Swagger | Logging | Redis prefix | Automation |
|---|---|---|---|---|---|---|---|---|---|---|
| local | `docker` | Compose | default noop | default noop | default noop | default `fixture` | on | text | empty | none |
| integration | `docker` | Compose | default noop; test spy/simulation | default noop | default noop | default `fixture` | on | text | empty | local task |
| dev | default | dev MySQL + shared Redis | `.env` 전환(기본 noop) | `.env` Kakao | `.env` 전환(기본 noop) | `.env` `secretsmanager`(preflight 값 고정) | on | JSON, dev environment | `dev_` | `dev` push |
| prod | default | repository에 확정 정보 없음 | default noop | default noop | default noop | env 필수(무기본값 fail-fast) | off | JSON intended | empty | no app deploy workflow |

배포된 환경의 runtime 값은 전부 host `/home/ubuntu/app/.env`가 소유한다(workflow `-e` 주입 없음).
dev deploy pre-flight는 dev 고정값(`REDIS_KEY_PREFIX=dev_`·`APP_ENV=dev`·`APP_GEO_MODE=kakao`·
`SWAGGER_ENABLED=true`)과 `APP_AI_MODE`/`APP_PUSH_MODE`/`APP_TRACING_MODE`,
`APP_SUBJECT_MODE`(값 고정 `secretsmanager`)·`APP_SUBJECT_SECRET_ARN`(ARN 형식 + runtime secret
read·secret 내용 계약 검증 + `user_subject_links` schema 검사 동반)을 exact-one으로 검증한다.
`SPRING_PROFILES_ACTIVE`는 `.env`에 없는 것이 정상이고 값에 `docker`가 포함되면 실패한다(docker
프로필의 위험한 배포 기본값 차단). `AWS_REGION`은 없거나 workflow
region과 같아야 하고 AWS credential/profile/endpoint override는 금지한다. 이들 계약을 위반하면 기존
container를 내리기 전에 실패한다. `APP_TRACING_MODE`는 앱이 소비하지 않는 pre-flight
전용 계약 키다 — `otlp`면 `JAVA_TOOL_OPTIONS`(-javaagent)와 `OTEL_*` 세트를 dev 고정값 byte 단위로
요구하고(`OTEL_TRACES_SAMPLER`만 non-empty 유연 — 부하 테스트 ratio 전환 계약), `noop`이면 두
계열의 잔존을 금지한다(스위치만 내려간 "조용한 부분 off" 차단). 상세 목록은 deployment.md Preflight. firebase 전환 시 ADC 경로(`GOOGLE_APPLICATION_CREDENTIALS`)도
`.env`가 소유하고 pre-flight가 service-account 파일 존재·가독성을 검사한 뒤 read-only mount만
추가한다. `APP_COMMIT_SHA`는 배포 workflow가 `.env`에
원자 upsert하는 유일한 key다.

prod 행은 repository에서 확인되는 application default와 automation 부재를 나타낸다. 실제 prod
runtime 구성은 저장소가 소유하지 않으며 production workflow도 아직 없다.

모든 profile에서 app port와 분리된 management port 9090을 사용한다. Actuator의 공통
`environment` metric tag는 `APP_ENV`를 쓰며 미주입 local/integration은 `local`, dev는 `.env`의
`APP_ENV=dev`가 된다. management endpoint의 실제 네트워크 접근 허용은 환경별 SG가 소유한다.

dev monitoring 자산은 별도 private host에서 실행되고 prod는 수집하지 않는다. monitoring
host가 dev WAS management 9090, dev host node 9100, dev MySQL 3306, shared Redis 6379와 dev ELK
9200으로 나가는 source-limited 경로만 갖는다. 유일한 인바운드 예외는 trace 수집이다 — Tempo의
OTLP는 push 모델이라 dev WAS → monitoring TCP 4317(gRPC) 인바운드를 허용하며, source는 dev WAS
전용 마커 SG `laimory-monitoring-proxy-source-sg`(Grafana 3000 인바운드와 같은 SG)로 제한한다.
`laimory-was-sg`는 source로 쓰지 않는다(stopped prod-was에도 부착돼 있어 prod 기동 시 의도 없이
열린다). rollback은 monitoring SG의 4317 규칙 1건 삭제다. Grafana는 dev WAS nginx/SSM을 통해서만
접근하며, monitoring 장애는 application 배포·health gate 의존성이 아니다.

## Configuration Names

값이나 credential은 기록하지 않고 이름과 역할만 다룬다.

- DB/Redis connection and `REDIS_KEY_PREFIX`
- `JWT_SECRET`, Google/Kakao OAuth client names
- tracing(#277 — 앱 미소비, deploy pre-flight·JVM/agent가 소비): `APP_TRACING_MODE`,
  `JAVA_TOOL_OPTIONS`(-javaagent 주입), `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`,
  `OTEL_EXPORTER_OTLP_PROTOCOL`, `OTEL_TRACES_SAMPLER`, `OTEL_METRICS_EXPORTER`,
  `OTEL_LOGS_EXPORTER`, `OTEL_INSTRUMENTATION_JDBC_DATASOURCE_ENABLED`,
  `OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS`
- `APP_AI_MODE`, `APP_GEO_MODE`, `KAKAO_REST_API_KEY`, `APP_GEO_LOOKUP_CONCURRENCY`,
  `APP_GEO_MAX_UNIQUE_COORDINATES`(공개 제품 상한 — 운영 tuning으로 낮추지 않음)
- Kakao 전용 HTTP 자원 경계(`app.geo.http.*`·`app.geo.retry.*`·`app.geo.circuit.*` — 같은 이름의
  upper-snake env가 override, kakao mode에서만 소비·기동 시 교차 validation):
  `APP_GEO_HTTP_POOL_MAX_CONNECTIONS`, `APP_GEO_HTTP_POOL_PENDING_ACQUIRE_MAX_COUNT`,
  `APP_GEO_HTTP_POOL_PENDING_ACQUIRE_TIMEOUT`, `APP_GEO_HTTP_CONNECT_TIMEOUT`,
  `APP_GEO_HTTP_RESPONSE_TIMEOUT`, `APP_GEO_HTTP_LOGICAL_CALL_TIMEOUT`,
  `APP_GEO_HTTP_POOL_MAX_IDLE_TIME`, `APP_GEO_HTTP_POOL_MAX_LIFE_TIME`,
  `APP_GEO_HTTP_POOL_EVICTION_INTERVAL`, `APP_GEO_RETRY_MAX_ATTEMPTS`(1 또는 2),
  `APP_GEO_RETRY_FIRST_BACKOFF`, `APP_GEO_RETRY_MAX_BACKOFF`, `APP_GEO_RETRY_JITTER`,
  `APP_GEO_CIRCUIT_SLIDING_WINDOW_SIZE`, `APP_GEO_CIRCUIT_MINIMUM_NUMBER_OF_CALLS`,
  `APP_GEO_CIRCUIT_FAILURE_RATE_THRESHOLD`, `APP_GEO_CIRCUIT_WAIT_DURATION_IN_OPEN_STATE`,
  `APP_GEO_CIRCUIT_PERMITTED_CALLS_IN_HALF_OPEN`
- `APP_PUSH_MODE`, `GOOGLE_APPLICATION_CREDENTIALS`(credential 값이 아니라 컨테이너 내부 JSON 파일 경로)
- subject 매핑 HMAC(#282): `APP_SUBJECT_MODE`(`secretsmanager|fixture` — provider 선택은 이 mode
  property 단일 축이고 배포 기본 프로필은 무기본값 fail-fast), `APP_SUBJECT_SECRET_ARN`(key 값이
  아니라 대상 Secrets Manager secret의 ARN 식별자 — key는 기동 시 `GetSecretValue` 1회로만 로드).
  `app.subject.fixture-key` 기본값은 docker 프로필만 소유해 배포 기본 프로필의 fixture mode는
  무기본값 property로 기동 실패하며, deploy preflight의 mode 값 고정·`SPRING_PROFILES_ACTIVE`
  docker 금지 가드가 이를 이중으로 막는다.
  docker의 `app.subject.fixture-key`는 checked-in deterministic 값으로 보호 대상 secret이 아니다
- `SWAGGER_ENABLED`
- `APP_COMMIT_SHA`(비밀 아님, dev deploy image SHA), `TIMELINE_STUCK_AFTER`
- `AWS_REGION`, S3/CDN and photo upload limit names
- `TIMELINE_PHOTO_DELETE_WORKER_ENABLED`, `TIMELINE_PHOTO_DELETE_CRON`,
  `TIMELINE_PHOTO_DELETE_ZONE`, `TIMELINE_PHOTO_DELETE_BATCH_SIZE`,
  `TIMELINE_PHOTO_DELETE_CONCURRENCY`, `TIMELINE_PHOTO_DELETE_MAX_BATCHES_PER_RUN`,
  `TIMELINE_PHOTO_DELETE_MAX_RUN_DURATION` (checked-in default는 worker on, 매일 `03:00`
  `Asia/Seoul`, process당 concurrency 1, batch 250, 최대 4 batch/60초)
- `DRAFT_CLEANUP_WORKER_ENABLED`, `DRAFT_RETENTION_DAYS`, `DRAFT_CLEANUP_CRON`,
  `DRAFT_CLEANUP_ZONE`, `DRAFT_CLEANUP_BATCH_SIZE`, `DRAFT_CLEANUP_CONCURRENCY`,
  `DRAFT_CLEANUP_MAX_BATCHES_PER_RUN`, `DRAFT_CLEANUP_MAX_RUN_DURATION` (checked-in default는 worker on,
  7일 retention, 매일 `04:00` `Asia/Seoul`, process당 concurrency 1, batch 250, 최대 4 batch/60초)
- `USER_MEMORY_UPDATE_CRON`, `USER_MEMORY_UPDATE_ZONE`, `USER_MEMORY_UPDATE_RETENTION`
- `APP_ENV`

정확한 property mapping과 default는 `application*.properties`가 권위다.

Kakao 전용 HTTP 자원 경계의 checked-in default는 connection pool 20, lookup concurrency 20,
pending acquire queue 200이다(#262 — 지연 폭주 방지는 큐 길이가 아니라 acquire timeout 3s가 담당한다). `APP_GEO_HTTP_POOL_PENDING_ACQUIRE_MAX_COUNT=0`도 명시적 fail-fast
override로는 유효하지만, 서로 겹친 정상 draft에서 먼저 시작한 batch가 pool을 점유하면 후행 batch가
healthy Kakao provider에서도 local rejection을 받는다. #262 이후 local rejection은 품질 판정에 계수되지
않아 502가 아니라 해당 좌표만 fallback(partial)으로 강등된다 — 그래도 0은 좌표 누락을 크게 늘리므로
기본값이 아니며 이 제품 결과를 수용하는 환경에서만 사용한다.

## Invariants

- secret/credential 값을 knowledge에 복제하지 않는다.
- application default, host `.env` 소유 값과 아직 없는 production automation을 구분한다.
- shared Redis의 dev prefix를 application key와 Spring Session namespace 모두에서 보존한다.

## Known Gaps

- production application deploy workflow와 확정된 production runtime injection이 없다.
- local과 deployed environment의 실제 OAuth/S3 동작에는 별도 credential 운영이 필요하다.

## Update When

profile, default/override, dependency topology, environment variable 이름, Swagger/logging/Redis prefix 또는
automation이 바뀔 때 갱신한다.

## Validation

```bash
docker compose config --quiet
rg -n 'APP_ENV|APP_AI_MODE|APP_GEO_MODE|SWAGGER_ENABLED|REDIS_KEY_PREFIX' \
  src/main/resources .github/workflows
```
