# Observability

## Scope

transaction ID, HTTP access log, 환경별 output, dev ELK와 metrics/alert pipeline의 현재 계약을 설명한다.

## Read When

logging filter/field/level, error handling, logback, Docker logging, Filebeat/Elasticsearch/Kibana,
Prometheus/Grafana/exporter/dashboard/alert를 바꿀 때 읽는다.

## Authoritative Sources

- `TransactionIdFilter`, `TransactionIds`, `RequestLogAttributes`, `HttpAccessLog`
- `GlobalExceptionHandler`, `ExceptionType`, `logback-spring.xml`
- `.github/workflows/deploy.yml`
- `deploy/elk/*`
- `deploy/monitoring/*`
- `terraform/README.md`, `terraform/user_data/was.sh.tftpl`

## Current Request Tracing

- 서버가 모든 request에 새 UUIDv7 transaction ID를 만든다.
- request header의 client-provided ID는 사용하지 않는다.
- MDC key는 `transactionId`다.
- reactive 경계(지오코딩 WebClient)는 MDC가 이벤트루프 스레드로 전파되지 않으므로, blocking 경계
  (`GeocodingService`)가 구독 시 transactionId만 Reactor Context에 싣고 signal 로그 실행 순간에만
  MDC를 복원·원복한다(`TxContextLogging` — worker 스레드 잔류 금지).
- client 노출은 response header `Transaction-Id` 하나뿐이고 envelope에는 없다.
- filter가 request당 한 줄 `http_request_completed` access log를 남긴다.
  예외는 `ExcludedPaths`뿐 — 등재 기준은 **"정상 완료가 아무 정보도 담지 않는 트래픽"**(헬스체크·favicon)이고
  **정상 완료만** 생략된다. 에러·미처리 예외는 경로와 무관하게 남는다. tx 발급·MDC는 제외와 무관하게 유지된다.
- fields는 `HttpAccessLog` record가 스키마다: `event`, `method`, `path`, `status`, `latencyMs`,
  `errorCode`(client 계약), `exceptionType`(내부 실패 사유), `errorDetail`(예외 클래스명·검증 메시지),
  `clientIp`, `requestBody`, `responseBody`. field 추가는 record 한 곳이며 null field도 명시적으로 출력한다.
- query string은 포함하지 않는다.
- **log level은 HTTP status가 아니라 `ExceptionType.logLevel()`이 정한다**(access log level의 SSOT —
  status는 client 계약, level은 서버 관점 심각도로 독립 축. 같은 status라도 내부 사유에 따라
  level이 다르다). 에러 없는 요청은 INFO, 정상 `/status`만 DEBUG.
- filter까지 전파된 unhandled exception은 effective status 500 + ERROR로 기록한다(매핑이 아니라 사실 기반).
- exception handler는 로그 대신 `ExceptionType`(+detail)을 request attribute로 심어 access log에 합류시킨다.
  stacktrace는 catch-all과 MVC가 직접 처리하는 5xx만 남긴다(둘 다 filter가 예외 객체를 못 보는 경로).
- 보안 감사·외부 호출 진단용 서비스 로그(재사용 탐지, verifier mismatch, callback replay, 지오코딩 실패,
  미지원 photo 타입)는 access log에 없는 데이터를 가진 **독립 이벤트**로 자기 level을 소유한다 —
  같은 exception을 중복 logging하지 않는 원칙은 유지.
- FCM 발송 서비스 로그의 허용 필드는 `taskId`·status·target/success/failure/invalid **개수**와
  오류 분류(`MessagingErrorCode` 집계)뿐이다. FID 원문·Firebase 응답 body·credential은 application
  log·예외 메시지에 남기지 않는다(sender unit test가 고정).

**요청·응답 값은 적극적으로 log한다. 금지는 진짜 비밀만: token, password, credential, presigned URL,
세션 값.** query string과 request/response header는 서명·token 채널이라 제외한다. 따라서 OAuth 302
`Location`의 `app_code`도 기록하지 않는다. 향후 header 로깅은 별도 마스킹·보안 검토 없이는 추가하지 않는다.
금지 대상을 예외 메시지에도 넣지 않는다.

JSON request와 정상 반환한 JSON response는 body마다 앞 64 KiB까지만 캡처하고 access log의
`requestBody`/`responseBody`에 최대 8,192자 text preview로 남긴다. 두 필드는 compact JSON,
`…`로 끝나는 절단 preview 또는 고정 placeholder를 담는 Elasticsearch `text`이며 object/nested field가
아니다. body 내부 key별 구조화 검색이나 JSON 재파싱을 계약하지 않는다. 이 경계는 임의 client key로 인한
dynamic mapping 증가·타입 충돌·문서 거부를 막는다.

- 객체와 배열을 재귀 순회해 token/password/secret/credential/authorization 계열 필드,
  `appCode`/`appVerifier`/`uploadUrl`/`firebaseInstallationId` alias를 값 타입과 무관하게 마스킹한다
  (FID는 민감 opaque 발송 식별자 — push 등록 body가 URL 대신 body로 FID를 받는 이유이기도 하다).
- 문자열 값의 대소문자 무관 `X-Amz-` 검사는 필드명 마스킹이 놓친 presigned S3 URL을 위한 denylist
  백스톱이다. 현재 사진 조회 CloudFront URL은 unsigned다. 다른 signed URL 유형을 도입하면 해당 서명
  파라미터도 별도 보안 검토한다.
- `/api/v\d+/auth/(token|refresh|logout)`의 non-empty JSON request는 파싱하지 않고
  `[masked auth body]`로 전체 마스킹한다. 비정상 auth body의 형태·누락/미지 필드·원문·fingerprint는
  access logging 범위가 아니며 status/errorCode/transactionId/clientIp로 조사한다.
- request cache는 MVC가 실제로 읽은 bytes만 가진다. 404/405/415처럼 body를 읽기 전에 거절하면
  client가 bytes를 보냈어도 `requestBody`가 null일 수 있고, malformed JSON은 소비된 부분 또는 전체가
  남을 수 있다. 로깅을 위해 request stream을 선행 소비하지 않는다.
- 필터까지 전파된 미처리 예외는 최초 capture가 최종 client 응답이 아닐 수 있어 `responseBody`를
  `[unavailable: unhandled exception]`로 남긴다. 이후 container `/error` body는 현재 한 줄 access log에서
  관찰하지 않는다.

body에는 위치·건강·알림 본문·기기 사진 URI 등 개인정보가 들어갈 수 있고 `clientIp`와 결합된다.
현재 적용 범위는 인증된 Kibana/SSM과 7일 ILM을 전제로 한 dev다. 미래 prod에서 body+IP logging을
활성화하기 전 데이터 소유자가 수집 목적·접근 통제·보존 기간·개인정보 고지 필요성을 승인하고 필요한
개인정보처리방침 변경을 먼저 완료해야 한다. 현재 prod 배포 경로가 없어 별도 runtime flag는 두지 않는다.

polling GET도 response body를 기록한다. `FAILED`도 HTTP 200 envelope일 수 있으므로 비-2xx만 기록하는
정책은 동등한 진단 대안이 아니다. 2026-07-17 dev rollout에서는 Android polling 설정을 확보하지 못했지만,
직전 7일 live access log의 polling GET이 2건이고 아래 대표 SUCCESS 기준 30 MB에 약 3,028건이 들어가며
dev 전용·실사용자 미도입 상태라 전체 response body 기록을 유지하기로 결정했다. 실사용자 도입 전이나
polling 트래픽이 유의미하게 증가하면 client interval·terminal 중단·동시 task 수를 다시 확보해 제외 여부를
재검토한다.

2026-07-16 `LogstashEncoder` 실인코딩 fixture(service/environment 포함)는 `PROCESSING` 652 B,
12 events × 4 photo items의 대표 `SUCCESS` 10,387 B, escape-heavy 8,192자 preview 16,899 B였다.
다른 로그를 무시한 상한 계산으로 30 MiB에는 대표 SUCCESS 약 3,028건이 들어간다. 이 수치는 단일 line
크기 여유를 확인하는 dev rollout 판단 근거이며, 실사용자 규모의 장기 보존 용량을 보장하지는 않는다.

외부에서 유입된 자유 문자열(요청 필드·예외 메시지·외부 시스템 출력)을 log에 넣을 때는
`LogSanitizer`를 통과시킨다 — CR/LF 제거(텍스트 로그 라인 위조 방지) + 길이 상한(keyword 색인 값이
Lucene 32,766B term 한도를 넘으면 access log 문서 전체가 ES에서 거부됨). `errorDetail`은
`GlobalExceptionHandler`의 조립 지점에서 일괄 정화(200자)되고 매핑 `ignore_above: 256`이 이중 방어다.
서버 생성 값(id·count·enum)은 유계라 대상이 아니다.

## Output by Environment

- `docker` profile은 사람이 읽는 text console log를 사용한다.
- 그 외 profile은 JSON stdout을 사용한다.
- JSON 공통 field는 `service=laimory`, `environment`다.
- dev workflow가 application environment 값을 주입한다.
- default profile은 JSON stream 순도를 위해 banner와 Hibernate `show-sql`을 끈다.

## Dev Log Pipeline

```text
Spring JSON stdout
→ Docker json-file
→ WAS Filebeat
→ Elasticsearch
→ Kibana
```

- Filebeat는 root로 Docker container log를 읽고 JSON decode 뒤 `service=laimory` event만 유지한다.
- index pattern은 `laimory-{environment}-YYYY.MM.dd`다.
- ILM retention은 7일이다.
- Elasticsearch/Kibana는 private dev ELK instance에서 실행되고 Kibana는 nginx `/kibana`로 proxy한다.
- ELK instance는 persistent Spot으로 상시 가동한다. 용량 회수 시 stop되고 용량 복귀 후 자동 재시작한다.
- ELK가 멈춘 동안 backfill 가능 범위는 app container의 30 MB rotated log에 제한된다.

## Application Metrics

- Actuator는 app API 8080과 분리된 management port 9090에서 동작한다.
- web endpoint는 `/actuator/health`와 `/actuator/prometheus`만 노출하고 discovery links와
  env/beans/configprops/heapdump/loggers 등은 노출하지 않는다.
- health 응답은 aggregate `status`만 보여 component/detail을 숨긴다.
- 공통 tag는 `application=laimory`, `environment=${APP_ENV:local}`이다.
- 표준 JVM/process/HTTP server·client/Hikari meter를 사용하고, HTTP server/client latency와 timeline
  callback에는 property에 선언한 고정 SLO bucket만 둔다. 전역 percentile histogram은 켜지 않는다.
- custom meter:
  - `laimory.timeline.draft.creation`: PROCESSING task 저장 성공 수
  - `laimory.timeline.task.terminal{result=success|failed}`: terminal task 저장 성공 수
  - `laimory.timeline.callback.duration`: callback handler 전체 처리 시간
  - `laimory.timeline.task.processing.stuck`: 10분 초과, 1시간 TTL 안인 PROCESSING task 수
  - `laimory.push.delivery{result=success|failed}`: FCM batch response가 확인한 발송 결과 수
  - `laimory.build.info{commit=<short SHA|local|unknown>}=1`: 실행 중인 앱 build
- custom label은 고정 `result`와 전용 build info의 `commit`만 사용한다. userId/taskId/transactionId/FID/좌표/raw URL·query/
  자유 입력/exception message는 tag로 쓰지 않는다.
- Fake AI callback은 URI template을 보존하며, Kakao WebClient의 URI function도 좌표·주소 query를
  low-cardinality tag로 만들지 않는다.
- management child context에는 application의 `TransactionIdFilter`가 등록되지 않는다. 방어적으로
  health/prometheus exact path도 정상 access log 제외 목록에 유지한다.
- 애플리케이션은 Prometheus/Grafana를 호출하거나 의존하지 않는다.

## Dev Metrics Rebuild Recipe

repository에는 private On-Demand t3.medium 한 대에서 Prometheus, Grafana, blackbox와 central
MySQL/Redis exporter를 실행하는 재구축 recipe가 있다. Prometheus는 30초 scrape, 7일 또는 12GB
retention과 persistent volume을 쓰고 public `/status` probe만 60초다. Grafana 3000만
loopback/private IP에 publish하며 Prometheus와 exporter port는 Docker network에만 둔다.

node_exporter는 monitoring, dev WAS, dev MySQL, Redis, ELK의 private interface:9100에만 bind하는
systemd service다. pinned release archive SHA를 검증하며 prod에는 설치하지 않는다. textfile collector는
root oneshot이 atomic rename한 `.prom`만 읽는다. monitoring에서는 5분 CloudWatch EC2/EBS와 1분
Elasticsearch health/latest-log을, dev WAS에서는 loopback Filebeat stats를 수집한다. 최근 log 시각은
무트래픽과 장애를 구분할 수 없어 alert하지 않는다. central mysqld exporter는 dev MySQL의 IP-scoped USAGE-only 계정으로 global
status/variables만 읽는다. Redis exporter ACL은 INFO/PING/CLIENT SETNAME만 허용하고 key pattern,
GET/SCAN/EVAL/SLOWLOG와 mutation을 허용하지 않는다.

Grafana는 `Laimory / Overview`, `JVM & Spring`, `Infrastructure`, `Logs` 네 dashboard를 file
provisioning한다. Prometheus datasource UID는 `prometheus`, Elasticsearch UID는 `elasticsearch-dev`다.
Elasticsearch API key는 `laimory-dev-*`의 read/view metadata와 cluster monitor만 갖고, Discord native
contact point는 firing/resolved를 모두 보낸다. alert message에는 raw log/body, transactionId,
user/task/FID, 좌표, exception 원문을 넣지 않는다. exporter HTTP scrape 성공과 backend 연결·인증
성공은 별도로 판단해 `mysql_up`/`redis_up` 실패도 alert한다.

Grafana `/grafana/` reverse proxy는 별도 allowlist가 non-empty일 때만 dev WAS user data에서
활성화된다. 빈 목록은 SSM port forwarding 전용이다. Prometheus target file은 Terraform이 실제 dev
private IP로 렌더하지만 live 반영은 Console/SSM runbook을 따르며 현재 repository 상태만으로 live
rollout 완료를 의미하지 않는다.

Grafana admin/encryption key, Elasticsearch API key, Discord webhook, MySQL/Redis exporter credential은
Git/S3/Terraform에 두지 않는다. host의 여섯 UID별 `0400` secret file 중 하나라도 비거나 owner/mode가
다르면 systemd가 fail-closed하고, 비밀이 필요 없는 Prometheus/blackbox만 먼저 기동할 수 있다. live
proxy는 Grafana 전용 nginx include로 관리해 기존 Kibana location을 보존하며, allowlist 밖에서는 slash
유무와 관계없이 `/grafana` 경로를 차단한다.
live dashboard/alert upgrade는 기존 provisioning 파일과 generated unit을 root-only로 백업한다.
provisioned alert 파일을 rollback할 때는 collector를 제거하기 전에 먼저 해당 UID를 `deleteRules`로
Grafana DB에서 지워 absent alert가 잘못 firing하지 않게 한다. operational 보강은 기존 Prometheus
`node` job/target을 바꾸지 않으므로 commit rollback에서도 해당 target을 제거하지 않는다.

## Runbook: access log field 추가 롤아웃

`index-template.json`은 field를 명시 매핑하지만 `dynamic: true`라, template 갱신 전에 앱이 먼저
배포되면 새 field가 dynamic mapping으로 `text`+`keyword` 멀티필드로 굳는다(기존 index 소급 불가).
setup 컨테이너는 최초 부팅 1회만 실행되므로 살아있는 ELK에는 수동 PUT이 필요하다.
**순서: 레포 template 수정(PR) → 아래 수동 적용 → dev 머지(=자동 배포).**

```bash
# 0) S3 부트스트랩 사본 동기화(신규 박스 재현용 — terraform apply 금지, 레시피 모드)
aws s3 cp deploy/elk/index-template.json \
  "s3://$(terraform -chdir=terraform output -raw backup_bucket)/bootstrap/elk/index-template.json" \
  --profile sandbox

# 1) 상시 가동 중인 ELK 박스에 SSM 접속(Spot interruption 중이면 자동 재시작을 기다린다)
aws ssm start-session --profile sandbox --target "$(terraform -chdir=terraform output -raw elk_instance_id)"

# 2) 박스 안에서: template PUT(이후 생성되는 index용). 비번은 secrets.auto.tfvars의 elk_elastic_password
ES=http://localhost:9200; PW='<elk_elastic_password>'
sudo aws s3 cp "s3://<backup_bucket>/bootstrap/elk/index-template.json" /home/ubuntu/elk/index-template.json
curl -sf -u "elastic:$PW" -X PUT "$ES/_index_template/laimory" \
  -H 'Content-Type: application/json' --data-binary @/home/ubuntu/elk/index-template.json

# 3) 현재 열린 index에도 _mapping PUT(template은 기존 index에 소급되지 않음) — 추가한 field만 명시
#    ignore_above는 기존 field에도 갱신 가능한 파라미터다
curl -sf -u "elastic:$PW" -X PUT "$ES/laimory-dev-*/_mapping" -H 'Content-Type: application/json' \
  -d '{"properties":{"clientIp":{"type":"ip","ignore_malformed":true},"requestBody":{"type":"text"},"responseBody":{"type":"text"}}}'

# 4) 확인: body에 keyword subfield가 없고 clientIp가 ip인지
curl -sf -u "elastic:$PW" "$ES/laimory-dev-*/_mapping"
```

머지 전 Kibana saved query/alert가 `message` 문자열 파싱에 의존하지 않는지 확인한다. access log의
`message`는 고정값 `http_request_completed`이며 `event` 등 top-level field 쿼리가 계약이다.

forwarded header는 Tomcat `RemoteIpValve`가 socket peer가 internal proxy일 때만 XFF/XFP를 신뢰한다.
dev/prod의 같은 호스트 nginx loopback만 internal proxy로 명시하며, AI 서버 등 사설망에서 애플리케이션
8080으로 직접 접근하는 peer의 forwarded header는 신뢰하지 않는다. `clientIp`는 valve 처리 후
`request.getRemoteAddr()`다.

## Health Signals

- `/status`: plain JSON DB connection probe
- `/api/v1/intro`: dev deploy health gate, DB와 app config row 확인

둘 다 Redis·Kakao·S3 readiness를 포괄하지 않는다.

## Invariants

- transaction ID는 response header 하나로만 노출하고 envelope에 중복하지 않는다.
- request URI에는 query string을 붙이지 않는다.
- app log field/index 변경은 Filebeat, template, ILM과 Kibana search까지 함께 확인한다.

## Known Gaps

- provisioning 자산은 live rollout 완료를 뜻하지 않는다. application metric change와 infra recipe가
  dev에 합쳐진 뒤 SSM identity/secret 구성, Discord firing/resolved, 24시간 soak가 별도로 필요하다.
- distributed tracing과 dependency-complete readiness endpoint는 없다.

## Update When

ID 생성/노출, MDC/access fields·levels, sensitive logging, output format, Docker rotation,
Filebeat/index/retention, metric endpoint/tag/meter 또는 health signal이 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test
docker compose up -d
./gradlew integrationTest
jq empty deploy/elk/ilm-policy.json deploy/elk/index-template.json
docker compose -f deploy/monitoring/docker-compose.yml config --quiet
jq empty deploy/monitoring/grafana/provisioning/dashboards/json/*.json
```
