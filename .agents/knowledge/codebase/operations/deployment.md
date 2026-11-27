# Deployment

## Scope

현재 dev·test 애플리케이션 배포(AWS), prod 무중단 배포(OCI)와 Docker runtime, 수동 운영 경계를 설명한다.

## Read When

deploy workflow, preflight, health gate, container, environment injection 또는 recovery를 바꿀 때 읽는다.

## Authoritative Sources

- `.github/workflows/deploy.yml`, `.github/workflows/deploy-oci.yml`, `.github/workflows/deploy-monitoring.yml`, `.github/workflows/ci.yml`
- `deploy/oci/deploy/*`
- `Dockerfile`
- `deploy/monitoring/*`
- live AWS, GitHub repository Variables/Secrets(instance 목록은 Secrets)와 host 상태
- `application.properties`, intro/status API implementation

## Current Application Deployment

`deploy.yml`은 AWS의 dev·test만 배포한다. **prod(OCI)는 이 workflow의 대상이 아니다** — `main` push는 실행되지
않고 수동 실행에도 prod 선택지가 없다. prod는 아래 OCI 절의 `deploy-oci.yml`이 배포한다. AWS prod WAS의 `.env`는 OCI DB를 가리킨 채 보관돼 있어, 그 host로 배포가 되살아나면 OCI DB에
migration이 적용되고 스케줄러가 겹칠 수 있다. harness가 `main` trigger·prod 선택지·`PROD_*` secret 참조의 복귀를 막는다.

환경은 **Resolve deploy environment step 한 곳**에서만 정해진다 — `dev` push는 dev, `test` push는
test(#400), `workflow_dispatch`는 필수 `environment` 입력(기본값 없음)을 따른다. 이후 어떤 step도
branch·event를 다시 보지 않고 이 step의 출력만 읽는다. 알 수 없는 환경이거나 해당 환경의 instance
목록·role ARN이 비면 AWS 접근 전에 실패한다.

배포 role도 이 step이 고른다 — dev는 기존 공용 role, test는 전용 OIDC role(`vars.
AWS_TEST_DEPLOY_ROLE_ARN`, trust가 `refs/heads/test`뿐이고 SSM 대상도 test instance 1대 한정)이다.
기존 role의 AWS 권한은 prod instance도 SSM 대상으로 허용하므로 이 단일 분기가 환경 혼선을 막는 지점이다.
`workflow_dispatch`로 `environment=test`를 고를 때는 **실행 ref도 `test`여야 한다** — trust가 다른 ref를
거부하므로 resolve가 자격증명 전에 명확한 진단으로 fail-closed한다(dev의 cross-ref dispatch는 기존 계약 그대로).

⚠️ test 브랜치를 **새로 만들거나 dev로 리셋한 직후의 push는 배포를 트리거하지 않는다.** 브랜치 생성
push는 비교할 diff가 없어(대개 dev HEAD와 동일 커밋) push 트리거의 `paths` 필터에 걸리는 변경 파일이
0개이기 때문이다. 빈 커밋 push도 같은 이유로 안 뜬다. 그 시점의 첫 배포는 deploy-existing
dispatch로 한다 — test ref에서 `environment=test`를 고르고, 직전 dev 배포가 빌드한 image의 commit
SHA와 ECR digest를 입력한다(digest는 `aws ecr batch-get-image`로 조회). 이후 image 입력 경로에
닿는 실변경 push부터는 자동 배포가 정상 동작한다.

환경별 instance 목록과 runtime 기대값은 Resolve 단계가 정한다. dev/test는 각각 단일 host다.

1. `dev`/`test` push 중 Docker image 입력이나 `deploy.yml` 자체가 바뀌면 자동 배포한다.
   test code·문서·monitoring-only 변경은 application을 재배포하지 않는다.
2. 환경별 concurrency group으로 직렬화하고 `cancel-in-progress: false`를 유지한다. 기본 대기 슬롯은
   한 개이며 추가 실행으로 대체될 수 있다. 이 제한은 AWS 콘솔 조작이나 workflow 종료 후 남은 SSM 작업까지
   잠그지 않는다.
3. OIDC로 환경별 deploy role을 assume한다. push는 commit SHA image를 build/push하고,
   deploy-existing은 입력 SHA tag와 digest가 일치하는지 ECR에서 확인한 뒤 digest로 배포한다.
4. 원격 script 하나가 기존 container를 유지한 채 `.env`·credential·환경/mode 검증, ECR pull, runtime UID 권한,
   subject secret/schema와 `app_config` preflight를 실행한다.
5. 모든 준비 검사 후 첫 stop 직전에만 `APP_COMMIT_SHA`를 temp+rename으로 원자 upsert한다.
   `.env`의 나머지 값·0600·owner를 보존한다.
6. 실행 중인 container는 `docker stop --time 120`으로 종료하고 제거한다. Docker 오류나 SIGKILL 종료 코드 137은
   무시하지 않으며 해당 시도에서 remove/run을 이어가지 않는다. 이미 종료됐거나 생성에 실패해 없는 container는
   재배포로 복구할 수 있다. `--pull=never`, `--env-file`로 새 container를 실행한다.
7. Flyway 시작 migration과 JPA 검증을 포함해 `/readyz`와 `/api/v1/intro`를 확인한다.
8. 임시 `.env` 제거, image prune 1회와 원래 종료 상태 보존은 원격 script의 EXIT에서 수행한다.
   prune 실패는 배포 성공·실패를 바꾸지 않는다.
9. SSM send 오류·취소·timeout 때는 추가 명령을 보내지 않고 실패한다. command와 host 상태를 수동 확인한다.
   GitHub polling 종료가 EC2의 실행 취소를 뜻하지 않는다.

## Prod Deployment (OCI)

`main` push(앱 이미지 입력 경로만) → `deploy-oci.yml` → host `laimory-deploy`. 순서·복귀·host 구조 계약·설치는
[OCI 배포 README](../../../../deploy/oci/deploy/README.md)가 소유한다. 저장소 자산의 존재가 host 설치·활성화를 뜻하지 않는다 —
host 설치, GitHub Environment `prod`, Tailscale identity, blue/green 구조 전환이 모두 끝나야 동작한다(실제 상태는 live가 권위).

- 빌드는 `ubuntu-24.04-arm`, 이미지는 GHCR 공개 패키지이고 host는 digest로 받아 revision label을 요청 SHA와 대조한다.
- 접속은 GitHub OIDC 기반 Tailscale 임시 기기(`tag:ci`, OCI 22번만)와 forced command다. CI가 host에서 할 수 있는 일은
  저장소별로 고정된 서비스(api 또는 ai)에 "이 SHA·digest 이미지를 배포"하는 것뿐이다.
- 교체는 blue/green이다. 새 색이 정상일 때만 nginx upstream을 옮기고, 실제로 새 색이 응답함을 확인한 뒤 옛 색을 정상 종료한다.
  실패하면 옛 색이 계속 서빙한다. 배포 본체는 systemd unit이라 SSH가 끊겨도 완주한다.
- 전환 동안 api 두 개가 함께 돈다. 보통 약 1분이고, 스크립트 상한의 합(health 90초 + 8초 + 전환 확인 30초 + 30초 +
  정상 종료 120초)은 약 4분 40초다. 금지 창이 job 5분 전에 시작하는 것은 이 상한 때문이므로, 상한을 늘리면 창도 늘린다. OCI는 `SERVER_COUNT=1`이라 03:30 고아 정리·04:00 draft 정리가 겹치면 같은 행을
  동시에 지우고, 21:00 리마인더는 정지 중 발송이 유실될 수 있어 KST 02:25~04:50·20:55~21:10에는 배포를 시작하지 않는다.
  스케줄 cron을 바꾸면 `laimory-deploy`의 창도 함께 고친다.
- Flyway migration은 새 색이 기동할 때 적용된다. 옛 색이 함께 서빙하므로 구 앱 호환 migration 원칙은 그대로다.
- `DEPLOY_PAUSED=true`는 push 빌드·배포를 건너뛴다. `workflow_dispatch`(`build-only`·`deploy`)는 통과한다.
- 수동 `deploy`는 `main` 이력에 있는 커밋만 받는다. `build-only`는 아무 ref나 빌드할 수 있지만 대기열(concurrency group)이 달라
  대기 중인 `main` 배포를 밀어내지 않는다.
- host는 대기 색이 실제로 확인한 이미지(digest)로 떴는지 대조한 뒤에야 health를 본다.
- host의 compose·nginx·Alloy 파일은 자동 반영하지 않는다(사람이 머지된 커밋의 파일을 설치).

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
live 자동화의 선행 조건은 repository의 monitoring instance/bucket Secret과 deploy role의 scoped S3
conditional PutObject·동일 bytes 검증용 GetObject·monitoring SSM 권한을 Console/검토된 CLI로
반영하는 것이다. 실제 AWS와 host 상태가 권위 원천이다.

장기 실행 container의 runtime env는 host `.env`가 단일 권위(SSOT)다. workflow는 `-e` override를
사용하지 않고 환경 고정값(Redis prefix·application environment·geo mode·Swagger)과 AI/push mode를
exact-one으로 검증만 하며, `APP_COMMIT_SHA`가 workflow가 `.env`에 쓰는 유일한 key다. 이름과 의미만
문서화하며 값이나 credential은 host `.env`가 권위다.

host `.env`는 container 생성 시 `docker run --env-file`로 읽힌다. 값을 바꾼 뒤 `docker restart`만 하면
기존 container environment가 유지되므로 새 값이 반영되지 않는다. runtime flag 활성화·롤백은 deploy
workflow 재실행 또는 기존 container stop/remove 뒤 동일 인자의 재생성이 필요하다.

### Preflight

현재 기존 container stop 전에 확인하는 계약(실패 진단은 key 이름·개수까지만 — 값 비출력):

- `JWT_SECRET` minimum length, `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`,
  `KAKAO_CLIENT_ID`/`KAKAO_CLIENT_SECRET` presence
- `APP_ADMIN_PORT=8081`은 dev exact-one, test는 key 부재. `.env` 선반영 없이 새 코드가
  배포되면 기존 container를 내리기 전에 실패한다.
- `SELECT COUNT(*) FROM app_config`가 1이어야 한다(dev/test 공통; test는 dev DB 공유).
  subject schema 검사 뒤·SHA upsert/stop 전에 같은 datasource와 MySQL client로 확인한다.
  query 실패·0행·복수 행은 fail-closed하고 데이터 병합·삭제는 자동화하지 않는다.
- 환경 고정값 exact-one: `REDIS_KEY_PREFIX` · `APP_ENV` · `APP_GEO_MODE` · `SWAGGER_ENABLED`가
  각각 정확히 한 줄이고 기대값과 byte 일치. 기대값은 Resolve step이 환경별로 주입한다
  (dev는 `dev_`/`dev`/`kakao`/`true`, test는 `test_`/`test`/`kakao`/`true`).
- `APP_AI_MODE` exact-one(`noop|fake|http|agentcore`); `http`면 non-empty `APP_AI_HTTP_BASE_URL`도
  정확히 한 줄, `agentcore`면 `APP_AI_AGENTCORE_RUNTIME_ARN`·`APP_AI_AGENTCORE_ENDPOINT`가 각각
  정확히 한 줄 non-empty이고 ARN은 배포 region의 full Runtime ARN이어야 한다(#338)
- `APP_PUSH_MODE` exact-one(`noop|firebase`). `firebase`일 때만:
  `GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json` exact-one과
  `/home/ubuntu/app/secrets/firebase-service-account.json` 존재·non-empty 검사 후, pull한 image의
  runtime user(appuser, UID 1001)로 `test -r`까지 통과해야 구 컨테이너를 중지한다(파일은 chown
  1001·0400 — root 관점 검사만으론 권한 문제를 못 잡음). 통과 시 read-only bind mount만 추가하며
  ADC 경로는 `.env`가 소유한다. `noop`이면 mount·credential 검사 없이 기동
- `APP_TRACING_MODE` exact-one(`noop|otlp`) — 앱이 소비하지 않는 pre-flight 전용 계약 키(#277).
  `otlp`면 `JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar`와 `OTEL_*` 세트를
  고정값 byte 단위 exact-one으로 요구한다(service name `laimory-<environment>`, endpoint
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
    계약 위반 secret은 새 앱이 `parse()`에서 기동 실패하므로 stop 전에
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
- optional admin port 8081은 `127.0.0.1`에만 bind한다. ALB listener/target group·SG ingress·Docker
  publish를 추가하지 않는다. 활성화돼도 다른 connector의 `/admin/**`는 404다.
- management port 9090도 host network에 bind된다. live 접근은
  monitoring source SG가 추가된 뒤에만 허용한다.
- `json-file` rotation: 10 MB × 3
- 현재 image SHA는 모든 meter의 공통 tag가 아니라 `laimory.build.info` 한 meter에만 노출한다.
- image build는 `-x test`
- ECR lifecycle은 최근 15개 image를 보존

## Health and Recovery

### Localhost Admin (#461)

코드 배포 전 별도 승인된 작업으로 dev/prod DB의 `app_config` count=1을 확인하고 각 WAS host의
`/home/ubuntu/app/.env`에 `APP_ADMIN_PORT=8081`을 정확히 한 줄 반영한다. test에는 넣지 않는다.
이것은 host 수정 권한이 필요한 선행 작업이다. count 위반 데이터의 정리는 별도 승인 없이 하지 않는다.
코드에는 이 기능이 있어도 실제 배포·host 활성화 여부는 live 상태로 확인해야 한다.

```bash
aws sso login --profile sandbox
deploy/monitoring/scripts/open-observability-tunnel.sh admin-dev
# prod는 WAS가 복수이면 정확한 Name을 명시한다(아래 둘 중 작업 대상 하나를 선택).
deploy/monitoring/scripts/open-observability-tunnel.sh admin-prod laimory-prod-was-01
deploy/monitoring/scripts/open-observability-tunnel.sh admin-prod laimory-prod-was-02
```

로컬 브라우저에서 `http://localhost:8081/admin/`로 접속한다. 원격·로컬 포트 모두 8081로 유지하며
dev/prod 터널을 같은 로컬 포트에 동시에 열지 않는다. 스크립트는 실행 시 Name으로 조회하고 prod는
Environment=prod도 요구한다(dev WAS는 해당 태그 없음). 0개·복수 후보 또는 SSM offline이면 실패한다.
IAM 권한은 EC2/SSM 상태 조회, 선택한 WAS의 `AWS-StartPortForwardingSession` StartSession,
자신의 session 종료 권한이 필요하다. instance ID·credential은 저장소에 넣지 않는다.

페이지 상단과 저장 확인 dialog의 환경을 확인한다. 약관은 새 버전의 원문을 먼저 게시하고 새 창에서
200·내용을 확인한 뒤 등록한다. 현재보다 높은 버전만 가능하고 즉시 current가 된다. 앱 설정은
최소·권장 양의 Long을 변경하며 최소 버전 상승 시 앱 업데이트 요구가 즉시 바뀐다. 성공 뒤 UI가 공개
`/api/v1/intro`를 다시 읽어 같은 값인지 확인한다. `debugTestMessage`는 읽기 전용이다.
브라우저는 Long 정밀도를 위해 JSON integer 원문을 사용한다. 큰 정수의 원문 조회를 지원하지 않는
구형 브라우저는 반올림하지 않고 오류를 표시하므로 최신 브라우저를 사용한다.

접근 통제는 loopback connector + 실제 local-port guard + exact Host allowlist이며 unsafe 요청은
paired local Origin + CSRF token + JSON body를 모두 요구한다. 관리자 GET은 이 경계를 통과하면
익명 접근 가능하며 로그인·JWT는 없다. 모든 관리자 endpoint는 공개 OpenAPI에서 제외한다.
SSM 권한과 해당 host의 local process 권한을 가진 사람은 관리 기능에 접근할 수 있으므로 host 자체가
신뢰 경계다. access log(environment·Transaction-Id·요청/응답)와 SSM session 기록만 남고,
요청을 운영자 identity에 결정적으로 연계할 수 없다. 기존 access 로그 보존은 89일이다.

작업 후 브라우저를 닫고 Ctrl+C로 터널을 종료한다. CSRF 세션이 만료되면 새로고침한다. 요청 실패로
저장 여부가 불확실하면 현재 값/이력을 다시 조회한 뒤 판단하고 자동 재시도하지 않는다.
기능 비활성 rollback은 별도 승인 후 `APP_ADMIN_PORT` 제거와 **수동 container 재생성** 또는 이전
image 재배포다. 새 workflow는 dev key 부재를 거절하므로 제거 후 단순 workflow 재실행은 안 된다.
이미 반영된 약관 INSERT·앱 설정 UPDATE는 앱 rollback 후에도 남는다.

### Existing health gates

- 앱 deploy gate는 메인 포트 `/readyz`(readinessState·DB·Redis)와 `/api/v1/intro`(DB·app_config)다.
- `/status`는 DB connection probe지만 deploy gate가 아니다. readiness가 Kakao·S3·AI 전체의 정상 동작을
  보장하는 것은 아니다.
- Prometheus/Grafana 장애는 앱 기동·요청·deploy health gate에 영향을 주지 않는다.
- dev/test 배포 중 health failure와 배포 완료 후 장애는 수동 복구한다. 수동 rollback은 ECR lifecycle(최근 15개
  보존)에서 이전 SHA를 다시 pull해 재배포한다.

PHOTO delete worker와 `timeline_photo_delete_jobs`는 #551에서 제거됐다. V12 `DROP TABLE`은 job entity를 가진
구 image와 호환되지 않으므로(Hibernate validate 기동 실패) V12 이후 그 이전 image로 rollback하려면 V1의
`CREATE TABLE timeline_photo_delete_jobs`를 먼저 승인받아 적용한다. host `.env`의 `TIMELINE_PHOTO_DELETE_*`는
더 이상 읽히지 않는다(정리는 선택, host 변경 건별 승인).

### Deployment time budgets

| 단계 | 기준 | 제한 |
|---|---|---|
| 앱 종료 | Boot 3.5.8 기본 graceful, lifecycle phase당 기본 30초 | Docker stop 최대 120초. SIGKILL(137)이면 배포 실패 |
| 앱 준비 | 90초 마감까지 readiness/intro polling | 요청별 connect 1초·전체 2초, 재시도 간격 2초. 마지막 검사/간격은 최대 6초 추가 가능 |
| SSM | 원격 command executionTimeout 900초, runner polling 900초 | delivery 지연 등으로 원격 상태가 불명확하면 수동 확인 |

Boot 기본 graceful을 사용하므로 application.properties에 같은 설정을 중복 추가하지 않는다.

HTTP drain과 background 작업 완료는 별개다. 기존 `DailyReminderWorker`는 claim commit 후 process가
종료되면 해당 알림이 누락될 수 있는 at-most-once 계약이며, 계정 삭제·draft cleanup·orphan 스위퍼 등 다른
worker도 각자의 DB 재시도 계약을 따른다.

## Manual Operations

Flyway는 모든 환경의 앱 시작 시 실행한다. [Flyway 운영 절차](../../../../docs/database/flyway-adoption.md)에
따라 기존 DB는 최초 구조 대조와 명시 baseline, 빈 운영 DB는 최초 CLI bootstrap을 subject schema와
`app_config` preflight 전에 완료한다. 이후 일반 migration은 앱이 자동 실행한다. 기존 DB의 실제 편입 여부와
Flyway 전용 계정(`SPRING_FLYWAY_USER`)의 DDL 권한은 live 조회로 확인한다(앱 DataSource 계정은 DML만 — [environments](environments.md)). 검사 대상 테이블을 바꾸는 PR은 preflight가 배포 중
구/신 schema 양쪽에 맞도록 함께 조정한다. 긴 SQL/비호환 변경은 별도 maintenance로 다룬다.

- 저장소는 전체 AWS topology와 신규 host 초기화를 자동화하지 않는다.
- live AWS, GitHub repository Variables/Secrets와 실제 host 상태가 운영 구성의 권위 원천이다.
- AWS 작업은 먼저 `sandbox` SSO를 확인하고 조회와 SSM 비변경 진단으로 제한한다. AWS·host 수정은
  대상·영향·rollback을 설명한 뒤 별도 승인받는다.
- monitoring bootstrap에는 비밀 없는 자산만 두고 credential은 host의 보호 파일에만 주입한다.
- nginx, DNS, TLS와 host runtime 변경은 현재 상태를 확인한 뒤 수동으로 적용하고 검증한다.
- AI dispatcher mode 전환(`APP_AI_MODE`)은 host `.env` 변경 + **container 재생성**으로만 반영된다.
  `agentcore` 활성화 순서는 ① AI Server가 wrapper `requestType` 라우팅을 배포 ② App runtime IAM role에
  대상 Runtime 한정 `bedrock-agentcore:InvokeAgentRuntime` 부여(대상·영향·rollback 설명 후 별도 승인)
  ③ `.env`에 `APP_AI_MODE=agentcore`와 Runtime ARN·endpoint를 넣고 재배포다. rollback은 `.env`를 이전
  mode로 되돌리고 다시 재생성하는 것뿐이며, 이미 접수된 task는 AI 결과 또는 TTL이 종결한다.
- TIMELINE 접수 대기열(#515) 활성화 순서: ① 큐와 WAS role의 SQS 권한 준비(AWS 변경 — 별도 승인) ② 이 기능이 든
  버전을 **모든 인스턴스에** 배포(플래그 기본 off라 동작 불변) ③ `.env`에 큐 URL과
  `APP_AI_DISPATCH_WORKER_ENABLED=true`를 넣고 재생성 ④ 그다음 `APP_AI_DISPATCH_TIMELINE_ROUTE=queue`로 재생성.
  ②를 건너뛰고 queue를 켠 채 롤링하면 구버전 인스턴스가 `DISPATCH_PENDING` task를 읽지 못해 500이 된다.
  rollback은 route를 `inline`으로 되돌려 재생성 → worker가 남은 메시지를 비우고 큐 경로 task 수명(6분)이 지난 뒤 worker
  off 순서다. 이 기능 이전 코드로 되돌리는 것은 `DISPATCH_PENDING` task가 남지 않은 뒤에만 한다.
- AWS prod 배포에 쓰던 repository Secrets `PROD_INSTANCE_IDS`·`PROD_TARGET_GROUP_ARN`과 deploy role의 prod SSM·ALB
  권한은 workflow가 더는 읽지 않는다(정리는 AWS 폐기 때 별도 승인).

maintenance나 장애 대응에서 `DEPLOY_PAUSED=true`로 두면 `dev` push 실행은 build·SSM 전송 없이 skip되어 기존 container와
`.env`를 유지하며, manual dispatch는 pause를 무시한다. `build-only.yml`은 입력받은 exact
40-char commit SHA를 checkout했는지 확인한 뒤 ECR push만 하고 SHA tag·digest를 summary에
기록한다. `deploy.yml` deploy-existing은 SHA tag가 가리키는 ECR digest를 기록된
`image_digest`와 SSM 전에 비교하고, 일치한 image만 tag가 아닌 digest reference로 pull·기동한다.
SHA tag→digest 조회에는 deploy role이 이미 가진 repository 한정 `ecr:BatchGetImage`를 사용한다.
`ecr:DescribeImages` 추가 권한은 요구하지 않는다. 이 입력 경로는 운영자가 승인한 exact image를
수동 재배포하는 경로다.

이전 image를 수동 재배포할 때는 ECR 사용 가능성과 적용된 DB schema의 구 앱 호환성을 먼저 확인한다.
앱 rollback은 Flyway가 적용한 DB 변경을 되돌리지 않는다. 자동 배포/Flyway 중단은 별도 명시 동의가 필요하다.
DB undo나 Flyway 비활성화로 우회하지 않는다.

`DEPLOY_PAUSED`는 이미 pause gate를 통과한 run에 소급되지 않으므로 maintenance 시작 전 진행 중인
application deploy run이 0건인지 확인한다.

## Invariants

- preflight와 health gate를 기존 container stop보다 앞뒤 어느 위치에서 수행하는지 정확히 유지한다.
  `APP_COMMIT_SHA` 원자 upsert는 모든 pre-stop 검사·pull 성공 뒤, 첫 stop 직전에만 수행한다.
- 장기 실행 `docker run`에 `-e`/`--env`를 추가하지 않는다 — runtime env는 host `.env`가 SSOT다.
  일회성 preflight `docker run --rm`은 이 제한 대상이 아니다.
- image prune은 원격 script EXIT에서 1회만 수행하고 배포 status를 바꾸지 않는다.
- remote script의 heredoc 본문은 `.github/scripts/test-deploy-contract.sh`가 추출·실행해 검증한다 —
  script 계약을 바꾸면 harness를 같은 변경에서 통과시킨다.
- deploy workflow가 읽는 이름과 GitHub repository 설정을 맞춘다. instance 목록
  (`DEV_INSTANCE_ID`·`TEST_INSTANCE_ID`)과 monitoring instance/bucket
  (`MONITORING_INSTANCE_ID`·`MONITORING_BACKUP_BUCKET`)은 Secrets, 나머지
  (`AWS_DEPLOY_ROLE_ARN`·`DEPLOY_PAUSED`)는 Variables다. Actions가 step의 `env:` 블록을
  로그에 그대로 echo하는데 Variable은 마스킹되지 않고 이 저장소는 PUBLIC이라, host 식별자를
  Variable로 되돌리지 않는다 — 두 deploy harness가 `vars.` 회귀를 검사한다.
- 배포 환경 판단은 Resolve step 한 곳에만 둔다. 다른 step이 branch·event를 다시 보고 환경을
  정하지 않는다 — harness가 이 단일 지점 계약을 검사한다.
- AWS 배포 trigger에 `main`을 되돌리지 않는다 — harness가 검사한다.
- host가 여러 대인 환경은 순차 배포한다. 실패 시 남은 host로 진행하지 않는다.
- application deploy trigger는 Docker image와 remote deploy 계약에 영향을 주는 path로만 제한한다.
- monitoring alert workflow는 관련 path로만 trigger하고 credential을 host 밖으로 전달하지 않는다.
- 저장소 변경만으로 live AWS나 host가 바뀐다고 설명하지 않는다.
- AWS·host 수정에는 운영자 승인과 영향 범위 review가 필요하다.

## Known Gaps

- prod는 단일 OCI host다. blue/green은 배포 중단만 없애고 host 장애에는 전체가 멈춘다.
- 단일 host dev/test는 무중단 구성이 아니며 automatic rollback도 없다. 전체 외부 의존성 readiness check는 없다.
- HTTP drain은 background 작업의 완료/재시도 계약을 대신하지 않는다.

## Update When

trigger/concurrency, image build, preflight, env injection, container rollout, health/rollback 또는
manual operation 경계가 바뀔 때 갱신한다.

## Validation

```bash
./gradlew build
docker build -t laimory:local .
bash .github/scripts/test-oci-deploy-contract.sh
.github/scripts/test-deploy-contract.sh
.github/scripts/test-monitoring-deploy-contract.sh
git diff --check
```

## 초안 정리 스케줄러 전환 (#474)

초안 선점 컬럼 제거 V2는 구 앱과 호환되지 않는다. 최초 전환은 자동 배포를 pause하고 초안 worker를
전체 중지한 뒤 Flyway target=1로 새 코드를 먼저 전체 적용한다(dev DB 공유 test 포함). 구 컬럼 참조가
모두 사라진 뒤 target override를 제거하여 V2를 적용하고, 담당 번호와 공통 worker-count를 확인한 뒤
worker를 재개한다. 상세 적용·복구·증설은 [전환 절차](../../../../docs/database/474-draft-cleanup-rollout.md)를 따른다.
