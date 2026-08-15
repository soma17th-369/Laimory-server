# Observability

## Scope

transaction ID, HTTP access log, 환경별 output, dev ELK와 metrics/alert pipeline의 현재 계약을 설명한다.

## Read When

logging filter/field/level, error handling, logback, Docker logging, Filebeat/Elasticsearch/Kibana,
Prometheus/Grafana/exporter/dashboard/alert를 바꿀 때 읽는다.

## Authoritative Sources

- `TransactionIdFilter`, `TransactionIds`, `RequestLogAttributes`, `HttpAccessLog`
- `GlobalExceptionHandler`, `ExceptionType`, `logback-spring.xml`
- `.github/workflows/deploy.yml`, `.github/workflows/deploy-monitoring.yml`
- `deploy/elk/*`
- `deploy/monitoring/*`
- live ELK, monitoring과 WAS host 상태

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
  `errorCode`(공개 numeric code의 10진 문자열 projection), `exceptionType`(내부 실패 사유),
  `errorDetail`(예외 클래스명·검증 메시지),
  `clientIp`, `userId`, `requestBody`, `responseBody`. field 추가는 record 한 곳이며 null field도 명시적으로 출력한다.
- `userId`는 인증에 성공한 요청에만 있고 public endpoint·401은 null이다. 값은 `JwtAuthenticationFilter`가
  `RequestLogAttributes.USER_ID` request attribute에 심은 사본에서 온다 — 완료 로그는 security chain
  바깥의 `finally`에서 찍혀 그 시점 `SecurityContextHolder`는 이미 비워져 있으므로 attribute가 유일한
  전달 경로다. mapping은 `long`이다(template `dynamic: true`의 자동 매핑과 타입을 맞춰 인덱스 간 충돌 방지).
- REST의 code는 JSON integer지만 access log `errorCode`는 기존 Elasticsearch `keyword` mapping을
  유지하기 위해 `"-1008"` 같은 문자열로 기록한다. live index field type과 template은 바꾸지 않는다.
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
- FCM 발송 서비스 로그의 허용 필드는 `taskId`·`taskStatus`·`targets`/`accepted`/`failed`/
  `invalidTargets` **개수**와
  오류 분류 집계뿐이다. 오류 분류는 실패 범위(`TARGET`/`CALL`)와 Firebase Messaging code를 우선
  기록하고, 없으면 platform `ErrorCode`로 fallback한다. 집계값은 영향받은 target 수라 전체 호출
  실패도 chunk 수가 아니라 chunk의 target 수를 더한다. FID 원문·Firebase 응답 body·credential은
  application log·예외 메시지에 남기지 않는다. `accepted`는 FCM 접수 성공이지 단말 수신·노출 성공이
  아니며, 발송 결과 summary는 무효 등록 DB 정리 전에 남긴다(sender/notifier unit test가 고정).

**요청·응답 값은 적극적으로 log한다. 금지는 진짜 비밀(token, password, credential, presigned URL,
세션 값)과 사용자 사생활 원문을 통째로 담는 지정 endpoint body(#281 — 아래 전체 마스킹 목록)다.**
query string과 request/response header는 서명·token 채널이라 제외한다. 따라서 OAuth 302
`Location`의 `app_code`도 기록하지 않는다. 향후 header 로깅은 별도 마스킹·보안 검토 없이는 추가하지 않는다.
금지 대상을 예외 메시지에도 넣지 않는다.

JSON request와 정상 반환한 JSON response는 body마다 앞 512 KiB까지만 캡처하고 access log의
`requestBody`/`responseBody`에 최대 65,536자 text preview로 남긴다. 두 상한은 성격이 다르다 —
캡처 상한을 넘으면 body를 파싱·마스킹하지 못해 preview를 통째로 버리고(`[too large: ...]`,
문구의 바이트 수는 상한 상수에서 파생한다), preview 상한은 이미 마스킹이 끝난 문자열을 자르므로
절단되어도 원문이 새지 않는다. 캡처 상한의 비용은 요청당 힙(request+response 2벌)이고
preview 상한의 비용은 ES 저장과 rotation 안의 backfill 건수다. 두 필드는 compact JSON,
`…`로 끝나는 절단 preview 또는 고정 placeholder를 담는 Elasticsearch `text`이며 object/nested field가
아니다. body 내부 key별 구조화 검색이나 JSON 재파싱을 계약하지 않는다. 이 경계는 임의 client key로 인한
dynamic mapping 증가·타입 충돌·문서 거부를 막는다.

- 객체와 배열을 재귀 순회해 token/password/secret/credential/authorization 계열 필드,
  `appCode`/`appVerifier`/`uploadUrl`/`firebaseInstallationId` alias를 값 타입과 무관하게 마스킹한다
  (FID는 민감 opaque 발송 식별자 — push 등록 body가 URL 대신 body로 FID를 받는 이유이기도 하다).
- 문자열 값의 대소문자 무관 `X-Amz-` 검사는 필드명 마스킹이 놓친 presigned S3 URL을 위한 denylist
  백스톱이다. 현재 사진 조회 CloudFront URL은 unsigned다. 다른 signed URL 유형을 도입하면 해당 서명
  파라미터도 별도 보안 검토한다.
- **method+path 판정이 body parsing·크기·content-type 검사보다 먼저다.**
  `/api/v\d+/auth/(token|refresh|logout)` request는 empty·비JSON을 포함해 항상 `[masked auth body]`다.
  사용자 사생활 원문을 통째로 담는 지정 11개 endpoint의 body는 파싱 시도 없이 고정
  `[masked privacy body]`로 전체 마스킹한다(#281) — request 6개(draft 생성 POST, Event PATCH,
  memo PUT, AI timeline result POST, AI callback POST, User Memory result POST), response 5개
  (draft polling GET, daily-records 목록·날짜·by-id GET, Event 단건 GET). malformed·oversize·비JSON·
  empty도 같은 placeholder다. AI input 응답(`GET /s/.../input`)은 대상이 아니다 — 저장 시점에 이미
  privacy 치환된 서버간 응답이다. 기존 field 기반 secret 마스킹은 비대상 경로에 그대로 유지된다.
  비정상 body의 형태·누락/미지 필드·원문·fingerprint는
  access logging 범위가 아니며 status/errorCode/transactionId/clientIp로 조사한다.
- request cache는 MVC가 실제로 읽은 bytes만 가진다. 404/405/415처럼 body를 읽기 전에 거절하면
  client가 bytes를 보냈어도 `requestBody`가 null일 수 있고, malformed JSON은 소비된 부분 또는 전체가
  남을 수 있다. 로깅을 위해 request stream을 선행 소비하지 않는다.
- 필터까지 전파된 미처리 예외는 최초 capture가 최종 client 응답이 아닐 수 있어 `responseBody`를
  `[unavailable: unhandled exception]`로 남긴다. 이후 container `/error` body는 현재 한 줄 access log에서
  관찰하지 않는다.

위치·건강·알림 본문·기기 사진 URI 등 사용자 사생활 원문을 통째로 담는 timeline·AI 경로의 body는 위
11개 endpoint 전체 마스킹으로 access log에 남지 않는다. 마스킹 밖 경로의 body에도 개인 데이터가 실릴
수 있고 `clientIp`·`userId`와 결합된다
(`userId`는 같은 줄에서 그 body가 누구의 것인지 직접 지목한다).
현재 적용 범위는 인증된 Kibana/SSM과 7일 ILM을 전제로 한 dev다. 미래 prod에서 body+IP logging을
활성화하기 전 데이터 소유자가 수집 목적·접근 통제·보존 기간·개인정보 고지 필요성을 승인하고 필요한
개인정보처리방침 변경을 먼저 완료해야 한다. 현재 prod 배포 경로가 없어 별도 runtime flag는 두지 않는다.

polling GET response body는 privacy 마스킹(#281)부터 `[masked privacy body]`다 — 이벤트 제목·부제·
질문·payload 등 사용자 사생활 원문을 통째로 담기 때문이다. 2026-07-17의 "전체 response body 기록 유지"
결정은 이 마스킹으로 뒤집혔고, FAILED 진단은 body 대신 status/errorCode/exceptionType/transactionId로
한다. 아래 preview 상한 산정 수치는 마스킹 도입 전 polling body 기준의 기록이다.

2026-07-16 `LogstashEncoder` 실인코딩 fixture(service/environment 포함, preview 8,192자)는
`PROCESSING` 652 B, 12 events × 4 photo items의 대표 `SUCCESS` 10,387 B, escape-heavy preview
16,899 B였고 30 MiB에 대표 SUCCESS 약 3,028건이었다.

2026-07-31 preview 상한을 65,536자로 올린 뒤 같은 fixture는 대표 `SUCCESS` 23,370 B,
escape-heavy 131,600 B이고 30 MiB에 약 1,346건이다. 대표 SUCCESS의 전체 JSON이 약 20,000자라
이전 상한에서 절단되고 있었기 때문이다 — **대표 body 전체를 남길 만큼 preview를 올리면 rotation 안의
backfill 건수가 줄어 두 목표는 양립하지 않는다.** dev 전용이고 직전 관측에서 polling GET이
7일간 2건이라 절대 건수가 제약이 아니라고 보아 진단 가능성을 택했다. 이 수치는 단일 line 크기 여유와
ELK 중단 시 backfill 범위를 확인하는 dev 판단 근거이며, 실사용자 규모의 장기 보존 용량을 보장하지 않는다.
실사용자 도입이나 polling 트래픽 증가 시 preview 상한과 함께 재검토한다.

외부에서 유입된 자유 문자열(요청 필드·예외 메시지·외부 시스템 출력)을 log에 넣을 때는
`LogSanitizer`를 통과시킨다 — CR/LF 제거(텍스트 로그 라인 위조 방지) + 길이 상한(keyword 색인 값이
Lucene 32,766B term 한도를 넘으면 access log 문서 전체가 ES에서 거부됨). `errorDetail`은
`GlobalExceptionHandler`의 조립 지점에서 일괄 정화(200자)되고 매핑 `ignore_above: 256`이 이중 방어다.
서버 생성 값(id·count·enum)은 유계라 대상이 아니다.

## Distributed Tracing (Tempo + OTel, #277)

- dev WAS는 OpenTelemetry javaagent로 요청을 **HTTP → 서비스 메서드 → JDBC(SQL)/Kakao
  WebClient/Redis** span으로 분해해 monitoring host의 Tempo(OTLP gRPC 4317)로 push한다.
  보관은 로컬 스토리지 48h, metrics generator는 끈다. 조회는 Grafana Tempo datasource.
- agent jar는 배포 이미지에 항상 탑재되고(`/otel/opentelemetry-javaagent.jar`), 활성화는 host
  `.env`의 `JAVA_TOOL_OPTIONS`만이 소유한다 — `APP_TRACING_MODE` pre-flight가 스위치 SSOT다
  (environments.md). env가 없는 local/integration은 agent가 아예 붙지 않아 완전 무영향이다.
- **trace/span 축은 `transactionId`와 독립 공존한다.** transactionId 발급·MDC·response header
  `Transaction-Id` 계약은 불변이다. agent의 Logback MDC instrumentation이 `trace_id`/`span_id`/
  `trace_flags`를 MDC에 주입하고 `LogstashEncoder`가 MDC 전체를 출력하므로 JSON 로그에 필드가
  실린다(logback 수정 없음) — Kibana 로그의 `trace_id`로 Grafana Tempo trace를 여는 연결 축이다.
- metrics/logs exporter는 `none`으로 고정한다 — 기존 Prometheus(metrics)·ELK(logs) 경로와
  중복 수집하지 않고 trace만 내보낸다.
- **query 민감값 redaction**: 서버 span `url.query`·클라이언트 span `url.full`에 query가 실리므로
  `OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS`가 redaction
  목록을 **full-override**로 소유한다 — 기본 서명 4종(`AWSAccessKeyId`·`Signature`·`sig`·
  `X-Goog-Signature`)에 핸드오프/OAuth `code`·`state`·`app_challenge`와 Kakao `x`·`y`·`query`를
  더한 전체 목록을 명시한다. 48h 보관 trace에 원문을 남기지 않는다(access log의 query 제외와
  같은 원칙). 목록 변경 시 기본 4종을 빼먹으면 서명류가 다시 노출된다(전체 대체 의미론).
- 메서드 단위는 `@WithSpan` **선택 계측**만 쓴다(콜트리 잡음·오버헤드 최소화): 현재
  `TimelineDraftTaskService.createDraftTask`, `SourceItemEnrichmentService.enrich`,
  `GeocodingService.lookupAll`, `TimelineDraftPreparationService.prepareDraft`. agent가 없으면
  애노테이션은 no-op이다. Hikari 커넥션 대기는 `jdbc-datasource` 계측(기본 off →
  `OTEL_INSTRUMENTATION_JDBC_DATASOURCE_ENABLED=true`)의 `getConnection` span으로 본다.

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
  - `laimory.timeline.task.processing.stuck`: 90초 초과, 3분 TTL 만료 전인 PROCESSING task 수
  - `laimory.push.delivery{result=success|failed}`: FCM batch response가 확인한 발송 결과 수
  - `laimory.subject.secret.load`: 기동 시 Secrets Manager HMAC snapshot load timer — 성공
    경로만 무tag로 기록한다(실패 시 context가 기동하지 않아 Prometheus가 meter를 수집할 수
    없는 죽은 관측 — 실패 관측은 기동 실패 로그와 deploy preflight가 담당)
  - `laimory.subject.mapping.operation{operation=create|lookup,result=success|rotated|missing|failed}`:
    subject mapping 생성·조회 timer. timer count가 결과별 건수를 겸하며 식별자는 tag에 넣지 않는다
  - `laimory.build.info{commit=<short SHA|local|unknown>}=1`: 실행 중인 앱 build
  - `laimory.geo.batch{outcome=success|partial|rejected|bug, failure_kind=none|transient|permanent|mixed}`:
    unique geo lookup batch(품질 판정 포함) timer — terminal마다 정확히 1회
  - `laimory.geo.http.logical{endpoint=coord2address|keyword, outcome=success|exhausted|local_rejected|not_permitted, failure_kind}`:
    retry·deadline 포함 logical HTTP call timer
  - `laimory.geo.http.attempts{endpoint, attempt=first|retry}`: 실제 구독된 wire attempt 수
    (circuit open 거절은 세지 않음)
  - `laimory.geo.http.retries{endpoint, failure_kind}`: 실제 schedule된 retry 수(직전 실패 분류 tag).
    logical deadline이 backoff 중 만료되면 다음 wire attempt가 시작되지 않아도 schedule 자체는 계수된다.
  - `laimory.geo.circuit.transitions{from, to}`: `kakao-local` circuit state transition 수
    (Resilience4j binder의 state/calls gauge와 별도, 의미 중복 계수 없음)
- Reactor Netty native `reactor.netty.connection.provider.*`(total/active/idle/pending/max/max.pending)는
  전용 pool 이름 `kakao-local`로 활성화돼 있다(kakao mode 한정).
- custom label은 고정 `result`·`operation`, build info의 `commit`, geo 전용
  `outcome`/`failure_kind`/`endpoint`/`attempt`/`from`/`to`만 사용한다.
  userId/taskId/transactionId/FID/subject/lookup key/좌표/주소/raw URL·query/
  자유 입력/exception message는 tag로 쓰지 않는다.
- Fake AI callback은 URI template을 보존하며, Kakao WebClient의 URI function도 좌표·주소 query를
  low-cardinality tag로 만들지 않는다.
- management child context에는 application의 `TransactionIdFilter`가 등록되지 않는다. 방어적으로
  health/prometheus exact path도 정상 access log 제외 목록에 유지한다.
- 애플리케이션은 Prometheus/Grafana를 호출하거나 의존하지 않는다.
- PHOTO delete worker의 checked-in 운영 cadence는 매일 03:00 `Asia/Seoul`이고, process당 concurrency 1,
  batch 250, 최대 4 batch/60초다. 정상 job도 최대 약 24시간 대기하며 missed run을 catch-up하지 않고
  다음 실행까지 MySQL에 보존한다. 이 worker는 custom meter와 전용 dashboard/alert를 등록하지 않는다.
  각 process가 run 시작 설정과 batch/run 종료의 claimed/relinked-cancelled/S3 요청·성공·실패·응답 누락,
  DB completion/이월, 단계별 오류 수와 소요 시간을 key=value application log로 남긴다.
- draft retention cleanup도 custom meter를 등록하지 않는다. 각 process가 run 시작 설정과 batch/run 종료의
  claimed/succeeded/failed/deleted/already-absent, PHOTO 삭제 요청·성공·실패·skip, DB/worker 오류 수와
  소요 시간을 key=value application log로 남긴다.

## Dev Metrics Assets

repository에는 Prometheus, Grafana, blackbox와 central MySQL/Redis exporter의 구성 자산이 있다.
Prometheus는 30초 scrape, 7일 또는 12GB
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

Elasticsearch의 `service=laimory AND environment=dev AND level=ERROR` count를 1분 histogram으로
평가해 최근 5분 합계가 1 이상이면 pending 없이 warning을 보낸다. 이 알림은 전체 서비스 장애를 뜻하지
않으며, notification의 runbook URL은 현재 dev Kibana data view에서 최근 15분 ERROR 문서와
`message`/`level`/`errorCode`/`path`/`exceptionType` 열을 여는 인증된 조사 경로다. WARN 단건은
notification하지 않고 dashboard 추세와 Kibana Discover에서 조사한다. critical은 기존 5xx ratio,
target/probe/backend down, OOM 같은 사용자 영향·장애 신호가 소유한다.
Logs dashboard의 `ERROR & WARN Logs` 데이터 포인트에는 Kibana data link가 있다. 클릭한 시각 전후
5분과 현재 environment, 클릭한 ERROR/WARN series를 Discover에 넘기고
`message`/`level`/`errorCode`/`path`/`exceptionType` 열을 연다. 링크에는 원문 로그를 넣지 않는다.

Grafana `/grafana/` reverse proxy는 별도 allowlist가 non-empty일 때만 dev WAS에서 활성화된다.
빈 목록은 SSM port forwarding 전용이다. Prometheus target file의 실제 IP와 적용 상태는 live host가
소유하며 현재 repository 상태만으로 rollout 완료를 의미하지 않는다.

Grafana admin username의 repository 기본값은 `laimory`이며 compose 최초 생성과 alert provisioning
reload가 같은 값을 사용한다. Grafana admin/encryption key, Elasticsearch API key, Discord webhook,
MySQL/Redis exporter credential은 Git/S3에 두지 않는다. host의 여섯 UID별 `0400` secret
file 중 하나라도 비거나 owner/mode가 다르면 systemd가 fail-closed하고, 비밀이 필요 없는
Prometheus/blackbox만 먼저 기동할 수 있다. live proxy는 Grafana 전용 nginx include로 관리해 기존
Kibana location을 보존하며, allowlist 밖에서는 slash
유무와 관계없이 `/grafana` 경로를 차단한다.
alert rule은 manifest가 소유하는 책임별 file-provisioning YAML로 관리하며 live EC2에서 직접 편집하지
않는다. commit SHA별 immutable S3 release는 conditional create로만 쓰고 checksum manifest를 마지막에
publish하며, 같은 SHA 재시도는 기존 bytes가 같을 때만 성공한다. host deployer는 root-only backup,
파일 집합·UID 검증, hot reload와 provisioning API의 expected UID 확인을 수행하고, release 도구는
성공 후에만 active 경로로 승격한다. rollback은 alerting 디렉터리의 Grafana-readable `0755` mode를
보존하면서 파일만 복구한다. release 사이에서 사라진 UID는 임시 `deleteRules`로 Grafana DB에서도
지우며 reload 또는 UID 확인 실패 시 이전 파일과 새 UID를 함께 복구한다. 일반 host memory는
MemAvailable 15% 미만, filesystem cache를 적극 사용하는 ELK는 10%
미만이 각각 10분 지속될 때 경고한다. alert 관련 `dev` merge는 별도 GitHub workflow가 commit SHA
release publish와 monitoring EC2 SSM 적용을 자동화하며, SSM 직전 `dev` HEAD 재확인으로 stale push의
host 적용을 막는다. 명시적으로 선택한 `workflow_dispatch` release는 허용한다. credential은 host의
root-only file에서만 읽는다. operational 보강은 기존 Prometheus `node` job/target을 바꾸지 않으므로
rollback에서도 해당 target을 제거하지 않는다.

## Runbook: access log field 추가 롤아웃

`index-template.json`은 field를 명시 매핑하지만 `dynamic: true`라, template 갱신 전에 앱이 먼저
배포되면 새 field가 dynamic mapping으로 `text`+`keyword` 멀티필드로 굳는다(기존 index 소급 불가).
setup 컨테이너는 최초 부팅 1회만 실행되므로 살아있는 ELK에는 수동 PUT이 필요하다.
**순서: 레포 template 수정(PR) → 아래 수동 적용 → dev 머지(=자동 배포).**

```bash
# 0) 조회로 확인한 대상에 S3 부트스트랩 사본 동기화
BACKUP_BUCKET='<confirmed backup bucket>'
ELK_INSTANCE_ID='<confirmed ELK instance ID>'
aws s3 cp deploy/elk/index-template.json \
  "s3://$BACKUP_BUCKET/bootstrap/elk/index-template.json" \
  --profile sandbox

# 1) 상시 가동 중인 ELK 박스에 SSM 접속(Spot interruption 중이면 자동 재시작을 기다린다)
aws ssm start-session --profile sandbox --target "$ELK_INSTANCE_ID"

# 2) 박스 안에서: template PUT(이후 생성되는 index용). 비밀번호는 승인된 secret 경로로 확인한다.
ES=http://localhost:9200; PW='<elk_elastic_password>'
sudo aws s3 cp "s3://<backup_bucket>/bootstrap/elk/index-template.json" /home/ubuntu/elk/index-template.json
curl -sf -u "elastic:$PW" -X PUT "$ES/_index_template/laimory" \
  -H 'Content-Type: application/json' --data-binary @/home/ubuntu/elk/index-template.json

# 3) 현재 열린 index에도 _mapping PUT(template은 기존 index에 소급되지 않음) — 추가한 field만 명시
#    ignore_above는 기존 field에도 갱신 가능한 파라미터다
curl -sf -u "elastic:$PW" -X PUT "$ES/laimory-dev-*/_mapping" -H 'Content-Type: application/json' \
  -d '{"properties":{"clientIp":{"type":"ip","ignore_malformed":true},"userId":{"type":"long"},"requestBody":{"type":"text"},"responseBody":{"type":"text"}}}'

# 4) 확인: body에 keyword subfield가 없고 clientIp가 ip, userId가 long인지
curl -sf -u "elastic:$PW" "$ES/laimory-dev-*/_mapping"
```

머지 전 Kibana saved query/alert가 `message` 문자열 파싱에 의존하지 않는지 확인한다. access log의
`message`는 고정값 `http_request_completed`이며 `event` 등 top-level field 쿼리가 계약이다.

현재 최외곽 ingress인 WAS nginx는 client-supplied `Laimory-Client-IP`를 전달/append하지 않고 자신이
관찰한 `$remote_addr` 한 값으로 덮어쓴다. application의 `TrustedEdgeRequestFilter`는 원본 socket peer가
정확히 `127.0.0.1`이고 header가 정확히 하나의 valid IPv4/IPv6 literal일 때만 이를 normalize해
downstream `request.getRemoteAddr()`로 노출한다. repeated/comma/malformed/missing header와 다른 peer는
socket address로 fallback하며 XFF와 User-Agent는 IP 결정에 사용하지 않는다. rejected 원문도 기록하지
않는다.

같은 loopback trust 경계의 단일 `X-Forwarded-Proto: https|http`만 scheme/secure/serverPort view를
변환해 OAuth HTTPS redirect와 Secure cookie를 보존한다. AI 서버 등 사설망에서 애플리케이션 8080으로
직접 접근하는 peer가 보낸 custom IP/XFP/XFF는 모두 무시한다. access log `clientIp`는 trusted-edge
filter 다음의 `TransactionIdFilter`가 보는 `request.getRemoteAddr()`다.

## Health Signals

- `/status`: plain JSON DB connection probe
- `/api/v1/intro`: dev deploy health gate, DB와 app config row 확인

둘 다 Redis·Kakao·S3 readiness를 포괄하지 않는다.

## Invariants

- transaction ID는 response header 하나로만 노출하고 envelope에 중복하지 않는다.
- request URI에는 query string을 붙이지 않는다.
- app log field/index 변경은 Filebeat, template, ILM과 Kibana search까지 함께 확인한다.

## Known Gaps

- alert rule 파일은 관련 `dev` merge에서 자동 rollout되지만 dashboard·collector·target·contact point 등
  다른 provisioning 자산은 여전히 live rollout 완료를 뜻하지 않는다. SSM identity/secret 구성,
  Discord firing/resolved와 24시간 soak도 별도로 확인한다.
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
