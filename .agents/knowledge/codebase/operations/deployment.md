# Deployment

## Scope

현재 dev 애플리케이션 배포, Docker runtime과 수동 운영 경계를 설명한다.

## Read When

deploy workflow, preflight, health gate, container, environment injection 또는 recovery를 바꿀 때 읽는다.

## Authoritative Sources

- `.github/workflows/deploy.yml`, `.github/workflows/deploy-monitoring.yml`, `.github/workflows/ci.yml`
- `Dockerfile`
- `deploy/monitoring/*`
- live AWS, GitHub repository Variables와 host 상태
- `application.properties`, intro/status API implementation

## Current Dev Deployment

1. `dev` branch push 중 Docker image 입력(`src/main`, Gradle build/wrapper, Dockerfile/dockerignore)이나
   `deploy.yml` 자체가 바뀐 경우에만 workflow를 시작한다. test·문서·monitoring-only 변경은
   application을 재배포하지 않는다.
2. `deploy-dev` concurrency group으로 배포를 직렬화한다.
3. GitHub OIDC로 AWS deploy role을 assume한다.
4. commit SHA tag Docker image를 ECR에 push한다.
5. SSM으로 dev WAS에 remote script를 보낸다. script는 첫 실패 가능 명령보다 앞에서 EXIT cleanup
   trap을 설치한다.
6. 기존 container를 내리기 전에 `.env` 계약을 preflight한다(secret presence + dev 고정값·mode
   exact-one, 값 비출력 — 아래 Preflight).
7. nginx no-query access log 설정을 idempotent하게 보정한다. `nginx -t` 실패는 배포를 중단한다.
8. ECR login 후 새 image를 pull하고, firebase 모드면 runtime UID 1001 가독성까지 검사한다.
   이어서 subject mapping preflight(#282 — mode·ARN, runtime secret read + secret 내용 계약 검증,
   `user_subject_links` schema 검사, 아래 Preflight)를 수행한다. harness가 pull → subject preflight →
   upsert 순서를 강제한다.
9. 모든 pre-stop 검사·pull이 성공한 뒤에만 `APP_COMMIT_SHA`를 같은 디렉터리 temp+rename으로 `.env`에
   원자 upsert한다(첫 stop 직전 commit point — 이전 실패는 기존 `.env` bytes·SHA를 보존한다).
10. 기존 `laimory` container를 stop/remove한다.
11. `-e`/`--env` 없이 `--env-file /home/ubuntu/app/.env`만으로 새 container를 실행한다(host network,
    rotated `json-file` logging; firebase면 read-only credential mount만 추가).
12. `/api/v1/intro`를 최대 90초 polling한다. 실패하면 새 container log를 출력하고 workflow를 실패시킨다.
13. 성공·실패 어느 종료 경로에서도 EXIT cleanup이 `docker image prune -af`를 정확히 1회 실행한다 —
    어떤 container도 참조하지 않는 tagged/dangling image가 제거되고, prune 실패는 고정 경고만 남기며
    원래 배포 status를 바꾸지 않는다.

## Monitoring Alert Rule Deployment

`deploy-monitoring.yml`은 `dev` push 중 alert manifest, `*-rules.yml`, alert release 도구 또는 workflow
자체가 바뀐 경우에만 별도로 실행된다. GitHub OIDC deploy role로 commit SHA별 S3 release를 게시하고,
monitoring EC2에 SSM command를 보낸다. release object는 `If-None-Match: *` 조건부 생성만 허용하고
같은 SHA 재시도는 기존 bytes가 같을 때만 성공한다. SSM 직전 현재 `dev` HEAD를 확인해 오래된 push
실행이 최신 규칙을 덮어쓰지 못하게 하되 명시적인 `workflow_dispatch` 선택은 허용한다. release의
deploy/validate 도구는 checksum 확인 후 staged 경로에서 실행하며, 규칙 적용과 reload가 성공한 뒤에만
active 경로에 설치한다. host deployer는 manifest/file/UID를 검증하고 root-only backup 후 Grafana
provisioning API를 hot reload한 다음 release의 모든 UID가 실제 조회되는지 확인한다. rollback은
Grafana-readable alerting 디렉터리 mode를 유지한 채 파일만 복구한다. reload 또는 UID 확인이 실패하면
기존 파일과 UID 상태를 복구하고 SSM command와 workflow를 실패 처리한다.

Grafana admin password는 monitoring host의 `0400` secret file만이 소유한다. workflow, S3 release,
SSM command와 process argument에는 credential을 전달하지 않는다. application deploy와 monitoring
deploy는 별도 concurrency group을 사용하며, alert와 무관한 merge는 monitoring deploy를 시작하지 않는다.
live 자동화의 선행 조건은 repository의 monitoring instance/bucket Variable과 deploy role의 scoped S3
conditional PutObject·동일 bytes 검증용 GetObject·monitoring SSM 권한을 Console/검토된 CLI로
반영하는 것이다. 실제 AWS와 host 상태가 권위 원천이다.

장기 실행 container의 runtime env는 host `.env`가 단일 권위(SSOT)다. workflow는 `-e` override를
사용하지 않고 dev 고정값(Redis prefix·application environment·geo mode·Swagger)과 AI/push mode를
exact-one으로 검증만 하며, `APP_COMMIT_SHA`가 workflow가 `.env`에 쓰는 유일한 key다. 이름과 의미만
문서화하며 값이나 credential은 host `.env`가 권위다.

host `.env`는 container 생성 시 `docker run --env-file`로 읽힌다. 값을 바꾼 뒤 `docker restart`만 하면
기존 container environment가 유지되므로 새 값이 반영되지 않는다. runtime flag 활성화·롤백은 deploy
workflow 재실행 또는 기존 container stop/remove 뒤 동일 인자의 재생성이 필요하다.

### Preflight

현재 기존 container stop 전에 확인하는 계약(실패 진단은 key 이름·개수까지만 — 값 비출력):

- `JWT_SECRET` minimum length, `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`,
  `KAKAO_CLIENT_ID`/`KAKAO_CLIENT_SECRET` presence
- dev 고정값 exact-one: `REDIS_KEY_PREFIX=dev_` · `APP_ENV=dev` · `APP_GEO_MODE=kakao` ·
  `SWAGGER_ENABLED=true` 각각 정확히 한 줄
- `APP_AI_MODE` exact-one(`noop|fake|http`); `http`면 non-empty `APP_AI_HTTP_BASE_URL`도 정확히 한 줄
- `APP_PUSH_MODE` exact-one(`noop|firebase`). `firebase`일 때만:
  `GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json` exact-one과
  `/home/ubuntu/app/secrets/firebase-service-account.json` 존재·non-empty 검사 후, pull한 image의
  runtime user(appuser, UID 1001)로 `test -r`까지 통과해야 구 컨테이너를 중지한다(파일은 chown
  1001·0400 — root 관점 검사만으론 권한 문제를 못 잡음). 통과 시 read-only bind mount만 추가하며
  ADC 경로는 `.env`가 소유한다. `noop`이면 mount·credential 검사 없이 기동
- `APP_TRACING_MODE` exact-one(`noop|otlp`) — 앱이 소비하지 않는 pre-flight 전용 계약 키(#277).
  `otlp`면 `JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar`와 `OTEL_*` 세트를
  dev 고정값 byte 단위 exact-one으로 요구한다(service name `laimory-dev`, endpoint
  `http://10.0.32.14:4317`, protocol `grpc`, metrics/logs exporter `none`, jdbc-datasource `true`,
  query redaction 전체 목록 — full-override라 부분 목록이면 기본 서명 4종까지 벗겨지므로 값 고정).
  `OTEL_TRACES_SAMPLER`만 non-empty 유연(부하 테스트 시 ratio 일시 전환 계약).
  `noop`이면 `JAVA_TOOL_OPTIONS`·`OTEL_*` 잔존 금지(스위치만 내려간 "조용한 부분 off" 차단)
- `SPRING_PROFILES_ACTIVE`는 `.env`에 없는 것이 정상이며, 값에 `docker`가 포함된 줄이 있으면
  실패한다 — docker 프로필은 dummy JWT 기본값·localhost DB 등 배포에서 위험한 기본값과 subject
  fixture-key 기본값을 켠다. subject provider 선택은 `app.subject.mode` property 단일 축이라
  (`@Profile` 게이팅 없음) 이 가드와 `APP_SUBJECT_MODE` 값 고정이 배포의 fixture 금지를 담당한다.
  진단은 key 이름과 고정 문구만 낸다
- subject mapping(#282) — 다른 `.env` 검사와 달리 image pull·UID 검사 뒤, `APP_COMMIT_SHA`
  upsert·첫 stop 전에 실행된다(harness가 순서 강제):
  - `APP_SUBJECT_MODE=secretsmanager` 값 고정 exact-one — 배포 환경이 fixture key로 조용히
    뜨는 사고를 값 자체로 차단한다
  - `APP_SUBJECT_SECRET_ARN` exact-one + `arn:aws:secretsmanager:` 접두 형식 검사
  - `AWS_REGION`은 미설정(앱 기본값) 또는 workflow region과 같은 한 줄만 허용하고, `.env`의 AWS
    credential/profile/endpoint override를 금지한다. host AWS CLI도 env/shared config를 제거한
    subshell에서 IMDS instance profile만 사용해 앱 container와 runtime identity 경계를 맞춘다
  - runtime secret read + 내용 계약 검증: host instance profile로 `GetSecretValue`를 1회 수행해
    `SecretString`을 shell 변수로만 받고(출력·echo·argv 어디에도 비적재), 앱
    `SecretsManagerSubjectHmacKeyConfig.parse()`와 1:1 규칙을 python3(host에 없으면 preflight
    실패)에 환경변수로만 넘겨 검증한다 — JSON object · `currentVersion` 정수 1~32767 ·
    `currentKey` base64 정확히 32바이트 · `previousVersion`/`previousKey` 동반 존재·version
    상이·key 상이·`previousKey`도 32바이트 · `SecretString` 부재(SecretBinary 전용)면 실패.
    계약 위반 secret은 새 앱이 `parse()`에서 기동 실패하므로(자동 rollback 없음) stop 전에
    잡는다. 실패 진단은 항목 이름만 내고 python stderr도 버린다 — secret 값은 어떤 경로로도
    로그에 닿지 않는다
  - mapping schema: 앱이 실제 소비하는 `DB_HOST`/`DB_USERNAME`/`DB_PASSWORD` exact-one·non-empty를 확인한 뒤
    datasource와 같은 `DB_HOST:3306/laimory`에 `mysql:8.0` one-shot container로 접속한다.
    `user_subject_links`가 정확히 3개 column만 갖고 각 type/nullability가 일치하며,
    `user_lookup_key` 단일-column PK와 `subject_id` 단일-column UNIQUE인지 1/0으로 판정한다
    (추가 raw `user_id`, nullable·unsigned type, composite PK/UNIQUE도 실패). password는 `MYSQL_PWD`
    env 전달로 docker 인자에 값을 넣지 않고 row·값도 출력하지 않는다. `ddl-auto=validate`라 schema
    불일치 시 "새 앱 기동 불가 + 구 컨테이너 이미 중지"
    다운타임이 되므로 stop 전에 잡는다. cleanup의 `docker image prune -af`가 어떤 container도
    참조하지 않는 `mysql:8.0` image를 매 배포 후 제거하므로, 이 검사는 배포마다 `mysql:8.0`을
    다시 내려받는다

`REDIS_*`, `KAKAO_REST_API_KEY`는 현재 preflight하지 않는다. 실제 앱 datasource가 소비하는 `DB_*`
3종은 #282 subject schema 검사가 presence(exact-one·non-empty)와 실제 DB 접속까지 함께 검증한다.
dev는 Kakao geo mode를 켜므로 API key 누락 시 기존 container 제거 후 새 앱 boot가 실패할 수 있다.
Firebase credential은 파일 mount로만 전달하며 즉시 완화책은 `.env`를 noop으로 되돌린 재배포다
(FID 등록 API/DB는 유지).

### Container

- Java 21 multi-stage image, runtime non-root UID 1001
- application port 8080, host network
- management port 9090도 host network에 bind된다. nginx는 Actuator를 proxy하지 않으며, live 접근은
  monitoring source SG가 추가된 뒤에만 허용한다.
- `json-file` rotation: 10 MB × 3
- 현재 image SHA는 모든 meter의 공통 tag가 아니라 `laimory.build.info` 한 meter에만 노출한다.
- image build는 `-x test`
- ECR lifecycle은 최근 15개 image를 보존

## Health and Recovery

- deploy gate는 `/api/v1/intro`다. DB 연결과 `app_config` row를 사용한다.
- `/status`는 DB connection probe지만 deploy gate가 아니다.
- 두 endpoint 모두 Redis, Kakao, S3 전체 준비 상태를 검증하지 않는다.
- Prometheus/Grafana 장애는 앱 기동·요청·deploy health gate에 영향을 주지 않는다.
- health failure 시 이전 image로 자동 rollback하지 않는다. 이전 image의 host-local cache는 cleanup이
  prune하므로 없을 수 있다 — rollback은 ECR lifecycle(최근 15개 보존)에서 이전 SHA를 다시 pull해
  재배포한다.

PHOTO delete worker는 checked-in default가 on이므로 live MySQL에 additive job table이 적용된 환경에서만
배포한다. 배포된 container는 다음 03:00 KST부터 pending job을 처리한다. 즉시 중지하거나 schema 선행
조건을 아직 충족하지 못한 환경은 host `.env`의 worker flag를 false로 바꾸고 deploy workflow를 재실행해
container를 재생성한다. pending job row는 수동 삭제하지 않는다. job은 보존 중인 원문 PHOTO Item을 FK로
참조하므로 backlog를 수동 정리할 때도 job만 또는 Item만 단독 삭제하지 않는다.

## Manual Operations

- 저장소는 전체 AWS topology와 신규 host 초기화를 자동화하지 않는다.
- live AWS, GitHub repository Variables와 실제 host 상태가 운영 구성의 권위 원천이다.
- AWS 작업은 먼저 `sandbox` SSO를 확인하고 조회와 SSM 비변경 진단으로 제한한다. AWS·host 수정은
  대상·영향·rollback을 설명한 뒤 별도 승인받는다.
- monitoring bootstrap에는 비밀 없는 자산만 두고 credential은 host의 보호 파일에만 주입한다.
- nginx, DNS, TLS와 host runtime 변경은 현재 상태를 확인한 뒤 수동으로 적용하고 검증한다.
- repository에는 production application deploy workflow가 없다.

subject cutover(#285)용 수동 배포 계약은 `deploy/subject-cutover/README.md`가 소유한다.
`DEPLOY_PAUSED=true`면 `dev` push 실행은 build·SSM 전송 없이 skip되어 기존 container와
`.env`를 유지하며, manual dispatch는 pause를 무시한다. `build-only.yml`은 입력받은 exact
40-char commit SHA를 checkout했는지 확인한 뒤 ECR push만 하고 SHA tag·digest를 summary에
기록한다. `deploy.yml` deploy-existing은 SHA tag가 가리키는 ECR digest를 기록된
`image_digest`와 SSM 전에 비교하고, 일치한 image만 tag가 아닌 digest reference로 pull·기동한다.
SHA tag→digest 조회에는 deploy role이 이미 가진 repository 한정 `ecr:BatchGetImage`를 사용한다.
`ecr:DescribeImages` 추가 권한은 요구하지 않는다. 이 수동 경로는 forward cutover용이며 legacy
rollback은 지원하지 않는다.

## Invariants

- preflight와 health gate를 기존 container stop보다 앞뒤 어느 위치에서 수행하는지 정확히 유지한다.
  `APP_COMMIT_SHA` 원자 upsert는 모든 pre-stop 검사·pull 성공 뒤, 첫 stop 직전에만 수행한다.
- 장기 실행 `docker run`에 `-e`/`--env`를 추가하지 않는다 — runtime env는 host `.env`가 SSOT다.
  일회성 preflight `docker run --rm`은 이 제한 대상이 아니다.
- EXIT cleanup(`docker image prune -af`)은 종료 경로마다 정확히 1회 실행하고 원래 배포 status를
  바꾸지 않는다.
- remote script의 heredoc 본문은 `.github/scripts/test-deploy-contract.sh`가 추출·실행해 검증한다 —
  script 계약을 바꾸면 harness를 같은 변경에서 통과시킨다.
- deploy workflow의 실제 variable 이름과 GitHub repository Variables를 맞춘다.
- application deploy trigger는 Docker image와 remote deploy 계약에 영향을 주는 path로만 제한한다.
- monitoring alert workflow는 관련 path로만 trigger하고 credential을 host 밖으로 전달하지 않는다.
- 저장소 변경만으로 live AWS나 host가 바뀐다고 설명하지 않는다.
- AWS·host 수정에는 운영자 승인과 영향 범위 review가 필요하다.

## Known Gaps

- application의 incomplete preflight, automatic rollback, dependency-complete readiness check와 prod app
  workflow가 없다.

## Update When

trigger/concurrency, image build, preflight, env injection, container rollout, health/rollback 또는
manual operation 경계가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew build
docker build -t laimory:local .
.github/scripts/test-deploy-contract.sh
.github/scripts/test-monitoring-deploy-contract.sh
git diff --check
```
