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
- live AWS, GitHub repository Variables/Secrets(instance 목록은 Secrets)와 host `.env`

## Current Matrix

| Environment | Spring profile | DB/Redis | AI | Geo | Push | Subject key | Swagger | Logging | Redis prefix | Automation |
|---|---|---|---|---|---|---|---|---|---|---|
| local | `docker` | Compose | default noop | default noop | default noop | default `fixture` | on | text | empty | none |
| integration | `docker` | Compose | default noop; test spy/simulation | default noop | default noop | default `fixture` | on | text | empty | local task |
| dev | default | dev MySQL + shared Redis | `.env` 전환(기본 noop) | `.env` Kakao | `.env` 전환(기본 noop) | `.env` `secretsmanager`(preflight 값 고정) | on | JSON, dev environment | `dev_` | `dev` push |
| prod | default | prod MySQL + shared Redis | `.env` 전환(기본 noop) | `.env` Kakao | `.env` 전환(기본 noop) | `.env` `secretsmanager`(preflight 값 고정) | off | JSON, prod environment | empty | `main` push |
| test | default | **dev MySQL 공유** + shared Redis | `.env` `http`(kakao-simulator) | `.env` Kakao(**simulator base URL + dummy key**) | `.env` noop | `.env` `secretsmanager`(dev와 같은 secret — DB 공유라 같은 HMAC key 필수) | on | **수집 없음**(Filebeat 미설치, host `docker logs`만) | `test_` | `test` push |

배포된 환경의 runtime 값은 전부 host `/home/ubuntu/app/.env`가 소유한다(workflow `-e` 주입 없음).
deploy pre-flight는 환경 고정값(`REDIS_KEY_PREFIX`·`APP_ENV`·`APP_GEO_MODE`·`SWAGGER_ENABLED`)과
`APP_AI_MODE`(`noop|fake|http|agentcore`)/`APP_PUSH_MODE`/`APP_TRACING_MODE`,
`APP_SUBJECT_MODE`(값 고정 `secretsmanager`)·`APP_SUBJECT_SECRET_ARN`(ARN 형식 + runtime secret
read·secret 내용 계약 검증 + `user_subject_links` schema 검사 동반)을 exact-one으로 검증한다.
`SPRING_PROFILES_ACTIVE`는 `.env`에 없는 것이 정상이고 값에 `docker`가 포함되면 실패한다(docker
프로필의 위험한 배포 기본값 차단). `AWS_REGION`은 없거나 workflow
region과 같아야 하고 AWS credential/profile/endpoint override는 금지한다. 이들 계약을 위반하면 기존
container를 내리기 전에 실패한다. `APP_TRACING_MODE`는 앱이 소비하지 않는 pre-flight
전용 계약 키다 — `otlp`면 `JAVA_TOOL_OPTIONS`(-javaagent)와 `OTEL_*` 세트를 고정값 byte 단위로
요구하고(`OTEL_TRACES_SAMPLER`만 non-empty 유연 — 부하 테스트 ratio 전환 계약), `noop`이면 두
계열의 잔존을 금지한다(스위치만 내려간 "조용한 부분 off" 차단). 상세 목록은 deployment.md Preflight. firebase 전환 시 ADC 경로(`GOOGLE_APPLICATION_CREDENTIALS`)도
`.env`가 소유하고 pre-flight가 service-account 파일 존재·가독성을 검사한 뒤 read-only mount만
추가한다. `APP_COMMIT_SHA`는 배포 workflow가 `.env`에
원자 upsert하는 유일한 key다.

환경 고정값의 기대값은 workflow가 소유한다 — `deploy.yml`의 Resolve deploy environment step이
branch(또는 수동 실행 입력)로 환경을 정하고 그 환경의 기대값을 원격 pre-flight에 주입한다.
dev는 `REDIS_KEY_PREFIX=dev_`·`APP_ENV=dev`·`SWAGGER_ENABLED=true`, prod는 빈 prefix·`APP_ENV=prod`·
`SWAGGER_ENABLED=false`, test(#400)는 `REDIS_KEY_PREFIX=test_`·`APP_ENV=test`·`SWAGGER_ENABLED=true`이며
`APP_GEO_MODE=kakao`는 전 환경 공통이다. 배포 role도 환경별로 갈린다 — test는 전용 OIDC role
(trust가 `refs/heads/test`뿐)을 쓰며 Resolve 단계가 환경별 role ARN을 함께 고른다.
test 환경은 추가로 **scheduled worker 5종을 `.env`로 전부 끈다** — dev와 같은 DB를 보므로 켜두면
dev의 작업 큐(특히 일일 리마인더 claim)를 소비해 dev 알림이 조용히 유실된다. 대가로 test는
background worker 동작을 재현하지 않는다. `APP_AI_MODE`·`APP_PUSH_MODE`는
값을 고정하지 않고 허용 집합만 검사하므로 host `.env`가 실제 값의 권위다. `APP_AI_MODE=agentcore`면
`APP_AI_AGENTCORE_RUNTIME_ARN`(배포 리전의 full Runtime ARN)과 `APP_AI_AGENTCORE_ENDPOINT`도 exact-one
non-empty로 함께 검증한다.

**prod runtime 값 자체는 여전히 저장소가 소유하지 않는다** — host `.env`와 live AWS가 권위다.
저장소가 아는 것은 "그 값이 무엇이어야 하는가"(기대값)까지이며, 실제로 무엇인지는 아니다.

모든 profile에서 app port와 분리된 management port 9090을 사용한다. Actuator의 공통
`environment` metric tag는 `APP_ENV`를 쓰며 미주입 local/integration은 `local`, dev는 `.env`의
`APP_ENV=dev`가 된다. management endpoint의 실제 네트워크 접근 허용은 환경별 SG가 소유한다.

monitoring 자산은 별도 private host에서 실행된다. monitoring host가 dev WAS management 9090,
dev host node 9100, dev MySQL 3306, shared Redis 6379와 dev ELK 9200으로 나가는 source-limited
경로를 갖고, 여기에 **prod MySQL 3306**이 더해진다(#358 binlog 오프호스트 스트리밍).

그 prod 경로는 성격이 다르므로 따로 다룬다 — monitoring host는 지표만 긁어오는 것이 아니라
**prod DB의 모든 row 변경을 스풀에 상시 보관**하게 된다. 계정은 전역 `REPLICATION SLAVE`·
`REPLICATION CLIENT`(DB 범위로 좁힐 수 없는 권한)이고 접속 host 고정 + 계정 단위 `REQUIRE SSL`로
제한하지만, 결과적으로 **이 host의 접근 통제 등급은 prod DB와 같아진다.** 루트 볼륨 EBS 암호화와
스풀 0700이 전제이며, 관측 host 접근 통제(#368)와 함께 평가한다. rollback은 prod MySQL SG의
3306 규칙 1건 삭제다.

유일한 인바운드 예외는 trace 수집이다 — Tempo의
OTLP는 push 모델이라 dev WAS → monitoring TCP 4317(gRPC) 인바운드를 허용하며, source는 dev WAS
전용 마커 SG `laimory-monitoring-proxy-source-sg`(Grafana 3000 인바운드와 같은 SG)로 제한한다.
`laimory-was-sg`는 source로 쓰지 않는다(stopped prod-was에도 부착돼 있어 prod 기동 시 의도 없이
열린다). rollback은 monitoring SG의 4317 규칙 1건 삭제다. Grafana는 `grafana.laimory.app`
(prod ALB·자체 Google OAuth, #368)과 SSM port forwarding으로 접근하며, monitoring 장애는
application 배포·health gate 의존성이 아니다.

## Configuration Names

값이나 credential은 기록하지 않고 이름과 역할만 다룬다.

- DB/Redis connection and `REDIS_KEY_PREFIX`
- `JWT_SECRET`, Google/Kakao OAuth client names
- `APP_EDGE_TRUSTED_PROXY_CIDRS`(#327 — 신뢰 엣지 판정용 CIDR 목록, 콤마 구분. ALB ENI가 사는 서브넷을
  넣으면 그 peer의 `X-Forwarded-For` 최우측만 client IP로 신뢰한다. checked-in 기본값은 비어 있어
  loopback 엣지만 남고, malformed 값은 기동 실패다. 자세한 계약은 observability.md)
- tracing(#277 — 앱 미소비, deploy pre-flight·JVM/agent가 소비): `APP_TRACING_MODE`,
  `JAVA_TOOL_OPTIONS`(-javaagent 주입), `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`,
  `OTEL_EXPORTER_OTLP_PROTOCOL`, `OTEL_TRACES_SAMPLER`, `OTEL_METRICS_EXPORTER`,
  `OTEL_LOGS_EXPORTER`, `OTEL_INSTRUMENTATION_JDBC_DATASOURCE_ENABLED`,
  `OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS`
- `APP_AI_MODE`, `APP_GEO_MODE`, `KAKAO_REST_API_KEY`, `APP_GEO_LOOKUP_CONCURRENCY`,
- AgentCore mode 전용(`app.ai.mode=agentcore`에서만 소비·기동 시 형식·리전 검증):
  `APP_AI_AGENTCORE_RUNTIME_ARN`, `APP_AI_AGENTCORE_ENDPOINT`(자격증명은 SDK 기본 체인 —
  access key property를 추가하지 않는다)
- dev 전용 AI 동기 테스트 endpoint(#394 — `app.ai.timeline-test.*`, **`APP_AI_MODE`와 완전히 별개
  스위치**라 비동기 경로가 `noop`이어도 동작한다): `APP_AI_TIMELINE_TEST_ENABLED`(기본 `false` —
  꺼져 있으면 controller 빈이 없어 `/t/api` 경로 자체가 부재), `APP_AI_TIMELINE_TEST_URL`(AI 동기 테스트
  endpoint의 absolute URL — `APP_AI_HTTP_BASE_URL`을 재사용하지 않아 다른 AI 인스턴스를 가리킬 수 있다),
  `APP_AI_TIMELINE_TEST_CONNECT_TIMEOUT`, `APP_AI_TIMELINE_TEST_READ_TIMEOUT`(AI
  `PIPELINE_TIMEOUT_SEC`(120s)보다 길어야 하며 위반은 기동 실패),
  `APP_AI_TIMELINE_TEST_MAX_REQUEST_BYTES`(응답 상한은 코드 상수).
  활성화하면 AI URL 누락·형식 오류가 기동 실패다(fail-fast). **호출자 인증 설정은 여기 없다** —
  `/t/api` Bearer token 검증은 security 계층 몫이고 현재 미구현이다. deploy pre-flight 검증 대상도
  아직 아니라 prod 차단은 앱 기본값 off 하나에 의존한다.
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
- `ACCOUNT_ERASURE_WORKER_ENABLED`, `ACCOUNT_ERASURE_QUIESCE_CRON`, `ACCOUNT_ERASURE_DELETE_CRON`,
  `ACCOUNT_ERASURE_ZONE`, `ACCOUNT_ERASURE_QUIESCE_DELAY`, `ACCOUNT_ERASURE_STALE_AFTER`,
  `ACCOUNT_ERASURE_GRACE_PERIOD_DAYS`, `ACCOUNT_ERASURE_WINDOW_DAYS`, `ACCOUNT_ERASURE_CONCURRENCY`, `ACCOUNT_ERASURE_MAX_BATCHES_PER_RUN`,
  `ACCOUNT_ERASURE_MAX_RUN_DURATION` (checked-in default는 worker on — 정지 15분마다, 삭제 매일
  `02:30` `Asia/Seoul`, 유예 7일 + 처리 창 3일. claim 크기는 설정으로 열지 않는다(항상 1건 —
  여러 건을 잡아 놓고 실행 예산이 끝나면 시작도 못 한 행이 3일 창 중 하루를 날린다). run당 처리량은
  `MAX_BATCHES_PER_RUN`이 정한다. 이 스위치는 활성화 게이트가 아니라 장애 시 즉시
  정지용이고 "언제부터 지우는가"는 `GRACE_PERIOD_DAYS`가 정한다. 단 `docker` 프로필은 off —
  15분마다 도는 정지 pass가 통합 테스트 job을 가로채지 않게 한다. `QUIESCE_DELAY`는 살아 있는
  draft/User Memory task TTL과 presign TTL을 넘겨야 하고 `STALE_AFTER`는 그 이하여야 하며, 둘 다
  기동 시 검증해 fail-fast한다 — `PHOTO_UPLOAD_PRESIGN_TTL`을 올리면 `QUIESCE_DELAY`도 함께 올려야 한다)
- `DAILY_REMINDER_WORKER_ENABLED`, `DAILY_REMINDER_CRON`, `DAILY_REMINDER_ZONE`,
  `DAILY_REMINDER_MAX_LATENESS`, `DAILY_REMINDER_BATCH_SIZE`, `DAILY_REMINDER_CONCURRENCY`,
  `DAILY_REMINDER_MAX_BATCHES_PER_RUN`, `DAILY_REMINDER_MAX_RUN_DURATION` (checked-in default는
  worker on — 리마인더가 사용자별 기본 ON이 된 뒤로(#318) worker on은 곧 전체 사용자 21:00 발송이라
  env는 문제 시 발송을 멈추는 kill switch다. 단 `docker` 프로필은 off — background claim이 통합
  테스트가 심은 due 행을 가로채지 않게 한다. 기본 매일 21:00 `Asia/Seoul` 1회(#385), 허용 지연 30분,
  process당 concurrency 1, batch 250, 최대 40 batch/5분 — 전원이 같은 21:00을 공유하고 초과분을
  받아갈 다음 tick이 없으므로, 그날 due를 한 run에서 모두 소화하도록 예산을 process당 10,000행으로
  잡는다. 부족하면 다음 날 run이 허용 지연을 넘긴 행을 발송 없이 skip하며 예산만 먹으므로, run 완료
  로그의 `lateSkipped`가 0이 아니면 예산 부족 신호다)
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
- application default, host `.env` 소유 값, workflow가 강제하는 기대값을 구분한다.
- shared Redis의 dev prefix를 application key와 Spring Session namespace 모두에서 보존한다.

## Known Gaps

- prod 배포는 `deploy.yml`의 환경 분기가 담당하지만 live 선행 조건 두 가지가 저장소 밖에 있다:
  prod host 목록 repository Secret(`PROD_INSTANCE_IDS`)과 deploy role의 prod SSM 권한. 상세는 deployment.md.
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
