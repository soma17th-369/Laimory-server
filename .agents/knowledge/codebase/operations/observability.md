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
  **정상 완료만** 생략된다. 에러 — 전파 예외·`ExceptionType` attribute·attribute 없는 5xx 응답 — 는
  경로와 무관하게 남는다. tx 발급·MDC는 제외와 무관하게 유지된다.
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
  level이 다르다). 에러 없는 요청은 INFO다(제외 경로의 정상 완료는 레벨이 아니라 로그 자체가 없다).
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
세션 값)과 사용자 사생활 원문(#281·#312 — 지정 endpoint는 아래 allowlist skeleton 마스킹으로 구조
필드만 남긴다)이다. 약관 두 GET은 응답에서 법률 원문이 사라진 뒤에도(#320) 같은 skeleton 대상으로
남는다.**
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
  사용자 사생활 원문을 담는 지정 17개 endpoint body는 **allowlist skeleton**으로
  마스킹한다(#281 전체 마스킹 → #312 skeleton 전환, 약관 2개 경로는 #303, Event 수동 생성 2개는
  #326/#361, AI 동기 테스트는 #394) — request 8개(draft 생성 POST, Event PATCH, memo PUT, Event 수동 생성 POST
  `/a/api/v\d+/timeline/daily-records/[^/]+/events`, AI timeline result POST, AI callback POST,
  User Memory result POST, dev 전용 AI 동기 테스트 POST `/t/api/v\d+/timeline/ai-results`),
  response 9개(draft polling GET, daily-records 목록·날짜·by-id GET, Event 단건 GET, Event 수동 생성
  POST — 입력 title/subtitle/memo와 연결 PHOTO payload를 echo하므로 request와 함께 대상, 공개 약관 GET
  `/api/v\d+/terms`, 동의 이력 GET `/a/api/v\d+/terms/agreements`, AI 동기 테스트 POST — AI가 만든
  Event 제목·부제·질문·장소를 그대로 돌려주므로 request와 함께 대상).
  AI 동기 테스트 경로는 staging을 거치지 않아 <b>저장 시점 치환이 없는 원문</b>이 request로 들어오므로
  마스킹이 유일한 방어선이다(치환은 AI로 나갈 때만 적용된다). 서버 발행 `taskId`는 상관키라 allowlist에
  포함해 로그에 남긴다. 감정 수정 PUT
  `.../daily-records/{recordDate}/emotion`(#325)은 body가 enum뿐이라 대상이 아니다.
  skeleton 규칙은 `AccessLogBodyMasker`의 allowlist가 SSOT다: 명시된 구조 필드(시각·enum·ID·rawId·
  status·`ApiResponse` envelope의 header/code/body·약관 termType/version/effectiveAt/
  acceptedAt 등)만 값을 남기고 목록 밖 필드는 타입 무관 `"***"`로 subtree째 붕괴한다(기본 마스크 —
  새 DTO 필드는 자동 마스크, title/payload/memo/userMemory·약관 `contentUrl`·envelope `message`가 대표
  대상). 약관 `contentUrl`을 allowlist에 넣지 않는 것은 값 자체를 로그에 남기지 않기 위해서다 —
  추적에 필요한 종류·버전은 구조 필드로 남으므로, URL이 필요하면 그 둘로 `term_documents.content_url`을
  조회한다(로그가 원본이 아니다).
  allowlist 텍스트 값도 shape guard(`[A-Za-z0-9_\-.:+/]{1,64}` 전체 일치) 통과 시만 남아 공백·비ASCII·
  장문 원문은 어떤 필드명 밑에서도 남지 않는다. `error`/`errorCode`는 숫자·null만 남긴다(폴링 numeric
  code는 유지, 콜백 자유 텍스트는 마스크 — "수신 후 폐기" 계약 유지). empty·캡처 상한 초과는 파싱
  없이, malformed·비JSON은 파싱 실패로 — 모두 고정 `[masked privacy body]`로 폴백해 형태 정보도
  남기지 않는다. AI input 응답(`GET /s/.../input`)은 대상이 아니다 — 저장 시점에 이미
  privacy 치환된 서버간 응답이다. 약관 동의 POST request는 원문 없이 type/version뿐이라 대상이
  아니다. 기존 field 기반 secret 마스킹은 비대상 경로에 그대로 유지된다.
  파싱 불가 body의 형태·원문·fingerprint는 여전히 access logging 범위 밖이며
  status/errorCode/transactionId/clientIp로 조사한다. 파싱 가능한 불량 body는 #312부터 skeleton으로
  구조(필드 존재·enum 값·개수)까지 관찰할 수 있다.
- request cache는 MVC가 실제로 읽은 bytes만 가진다. 404/405/415처럼 body를 읽기 전에 거절하면
  client가 bytes를 보냈어도 `requestBody`가 null일 수 있고, malformed JSON은 소비된 부분 또는 전체가
  남을 수 있다. 로깅을 위해 request stream을 선행 소비하지 않는다.
- 필터까지 전파된 미처리 예외는 최초 capture가 최종 client 응답이 아닐 수 있어 `responseBody`를
  `[unavailable: unhandled exception]`로 남긴다. 이후 container `/error` body는 현재 한 줄 access log에서
  관찰하지 않는다.

위치·건강·알림 본문·기기 사진 URI 등 사용자 사생활 원문과 약관 제목·원문 URL은 위 13개 endpoint
skeleton 마스킹으로 access log에 남지 않는다(구조 필드만 남는다). 마스킹 밖 경로의 body에도 개인 데이터가 실릴
수 있고 `clientIp`·`userId`와 결합된다
(`userId`는 같은 줄에서 그 body가 누구의 것인지 직접 지목한다).
현재 적용 범위는 인증된 Kibana/SSM과 7일 ILM을 전제로 한 dev다. 미래 prod에서 body+IP logging을
활성화하기 전 데이터 소유자가 수집 목적·접근 통제·보존 기간·개인정보 고지 필요성을 승인하고 필요한
개인정보처리방침 변경을 먼저 완료해야 한다. 2026-08-23 기준 prod 배포 경로가 생겼으므로 이 전제는 더 이상 성립하지 않는다 — body logging을
끄고 켜는 runtime flag를 두는 선택지가 열렸고, 그렇게 하면 개인정보 승인 절차를 공개 일정에서
분리할 수 있다. 아직 flag는 없다.

polling GET response body는 #281에서 전체 placeholder였고 #312부터 skeleton이다 — 이벤트 제목·부제·
질문·payload 등 원문 필드는 `"***"`, envelope·status·numeric `error`·이벤트/아이템 구조는 남는다.
2026-07-17의 "전체 response body 기록 유지" 결정은 #281로 뒤집혔고, FAILED 진단은 skeleton의
status/error와 access log field(errorCode/exceptionType/transactionId)로 한다. 아래 preview 상한
산정 수치는 마스킹 도입 전 polling body 기준의 기록이다.

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

- dev·test WAS는 OpenTelemetry javaagent로 요청을 **HTTP → 서비스 메서드 → JDBC(SQL)/Kakao
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

## Log Pipeline

```text
Spring JSON stdout
→ Docker json-file
→ WAS Filebeat
→ Elasticsearch
→ Kibana
```

- Filebeat는 root로 Docker container log를 읽고 JSON decode 뒤 `service=laimory` event만 유지한다.
  dev WAS와 **prod WAS 2대**에 배치돼 있고, 같은 Elasticsearch로 보낸다.
- index pattern은 `laimory-{environment}-YYYY.MM.dd`다. environment는 앱 로그의 필드에서 나오므로
  환경마다 index가 자동으로 갈린다. index template과 ILM은 `laimory-*`라 신규 환경을 이미 커버한다.
- ILM retention은 7일이다.
- Elasticsearch/Kibana는 private dev ELK instance에서 실행되고 Kibana는 prod ALB의
  `kibana.laimory.app` host 규칙으로 노출한다(ACM TLS 종단, Kibana 자체 로그인 유지).
- ELK instance는 persistent Spot으로 상시 가동한다. 용량 회수 시 stop되고 용량 복귀 후 자동 재시작한다.
- ELK가 멈춘 동안 backfill 가능 범위는 app container의 30 MB rotated log에 제한된다.

## Application Metrics

- Actuator는 app API 8080과 분리된 management port 9090에서 동작한다.
- web endpoint는 `/actuator/health`와 `/actuator/prometheus`만 노출하고 discovery links와
  env/beans/configprops/heapdump/loggers 등은 노출하지 않는다. health에는 readiness 그룹이 있고
  (`/actuator/health/readiness`) 같은 그룹이 메인 포트 `/readyz`로도 노출된다(additional-path).
- health 응답은 aggregate `status`(와 그룹 이름 목록)만 보여 component/detail을 숨긴다.
- 공통 tag는 `application=laimory`, `environment=${APP_ENV:local}`이다.
- 표준 JVM/process/HTTP server·client/Hikari meter를 사용하고, HTTP server/client latency와 timeline
  callback에는 property에 선언한 고정 SLO bucket만 둔다. 전역 percentile histogram은 켜지 않는다.
- 경보 규칙까지 물리지 않을 지표는 붙이지 않는다(write-only 지표 금지) — 예: 계정 삭제 작업(#305)
  PENDING backlog는 gauge 없이 runbook의 수동 SELECT로 확인한다.
- custom meter:
  - `laimory.timeline.draft.creation`: PROCESSING task 저장 성공 수
  - `laimory.timeline.task.terminal{result=success|failed}`: terminal task 저장 성공 수
  - `laimory.timeline.callback.duration`: callback handler 전체 처리 시간
  - `laimory.timeline.task.processing.stuck`: 90초 초과, 3분 TTL 만료 전인 PROCESSING task 수
  - `laimory.timeline.task.index.repair{index=global|user,operation=add|remove|expire,result=success|failed}`:
    최초 PROCESSING 등록·terminal 제거의 index 명령 실패 또는 사용자 index PEXPIRE=false를 task 재조회
    없이 같은 의도의 명령으로 한 번 재시도한 결과 수. 식별자와 Redis key는 tag에 넣지 않는다
  - `laimory.push.delivery{type=TIMELINE_COMPLETION|DAILY_REMINDER, result=success|failed}`:
    FCM batch response가 확인한 발송 결과 수. 차원은 고정 알림 종류와 결과뿐이다(#314).
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
- custom label은 고정 `result`·`operation`·`index`, build info의 `commit`, geo 전용
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
  다음 실행까지 MySQL에 보존한다. 처리 기회는 KST 생성일 기준 D+1~D+3 일일 실행뿐이고, 창을 벗어난
  미완료 job은 재시도 없이 보존하며 run 시작에 `expiredCount`만 담은 ERROR 로그를 남겨 기존
  `service=laimory AND level=ERROR` 경보를 발화시킨다(job ID·Item ID·object key 미포함).
  이 worker는 custom meter와 전용 dashboard/alert를 등록하지 않는다.
  각 process가 run 시작 설정과 batch/run 종료의 claimed/relinked-cancelled/S3 요청·성공·실패·응답 누락,
  DB completion/이월, 단계별 오류 수와 소요 시간을 key=value application log로 남긴다.
- draft retention cleanup도 custom meter를 등록하지 않는다. 각 process가 run 시작 설정과 batch/run 종료의
- **계정 삭제 worker(#302)**: 삭제 pass는 run 시작에 두 건수를 ERROR로 남겨 같은 경보에 태운다 —
  처리 창(접수일 D 기준 D+8~D+10)을 벗어나 재시도에서 제외된 `expiredCount`와 수동 확인 대기
  `manualReviewCount`다. 데이터와 job은 보존되며 로그에 userId·subjectId·jobId를 싣지 않는다.
  이 둘이 #302의 유일한 적체 감지 수단이다(별도 지표 없음 — 경보 미부착 지표 금지 원칙).
  claimed/succeeded/failed/deleted/already-absent, PHOTO 삭제 요청·성공·실패·skip, DB/worker 오류 수와
  소요 시간을 key=value application log로 남긴다.

## Metrics Assets

repository에는 Prometheus, Grafana, blackbox와 central MySQL/Redis exporter의 구성 자산이 있다.
Prometheus는 30초 scrape, 7일 또는 12GB
retention과 persistent volume을 쓰고 public `/status` probe만 60초다. Grafana 3000만
loopback/private IP에 publish하며 Prometheus와 exporter port는 Docker network에만 둔다.

node_exporter는 monitoring, dev WAS, test WAS(#400), dev MySQL, Redis, ELK와 **prod WAS 2대**의 private
interface:9100에만 bind하는 systemd service다. pinned release archive SHA를 검증한다. textfile collector는
root oneshot이 atomic rename한 `.prom`만 읽는다. monitoring에서는 5분 CloudWatch EC2/EBS와 1분
Elasticsearch health/latest-log을, dev WAS에서는 loopback Filebeat stats를 수집한다. 최근 log 시각은
무트래픽과 장애를 구분할 수 없어 alert하지 않는다. central mysqld exporter는 dev MySQL의 IP-scoped USAGE-only 계정으로 global
status/variables만 읽는다. Redis exporter ACL은 INFO/PING/CLIENT SETNAME만 허용하고 key pattern,
GET/SCAN/EVAL/SLOWLOG와 mutation을 허용하지 않는다.

Grafana는 `Laimory / Overview`, `JVM & Spring`, `Infrastructure`, `Logs` 네 dashboard를 file
provisioning한다. Prometheus datasource UID는 `prometheus`, Elasticsearch UID는 `elasticsearch-dev`다.
Elasticsearch API key는 `laimory-*`의 read/view metadata와 cluster monitor만 갖고, Discord native
contact point는 firing/resolved를 모두 보낸다. alert message에는 raw log/body, transactionId,
user/task/FID, 좌표, exception 원문을 넣지 않는다. exporter HTTP scrape 성공과 backend 연결·인증
성공은 별도로 판단해 `mysql_up`/`redis_up` 실패도 alert한다.

Elasticsearch의 `service=laimory AND level=ERROR` count를 environment terms로 나눠 1분 histogram으로
평가해 최근 5분 합계가 1 이상인 환경마다 pending 없이 warning을 보낸다. 이 알림은 전체 서비스 장애를 뜻하지
않으며, notification의 runbook URL은 `kibana.laimory.app`의 Kibana data view에서 전 환경 최근 15분 ERROR 문서와
`message`/`level`/`errorCode`/`path`/`exceptionType` 열을 여는 인증된 조사 경로다. WARN 단건은
notification하지 않고 dashboard 추세와 Kibana Discover에서 조사한다. critical은 기존 5xx ratio,
target/probe/backend down, OOM 같은 사용자 영향·장애 신호가 소유한다.
Logs dashboard의 `ERROR & WARN Logs` 데이터 포인트에는 Kibana data link가 있다. 클릭한 시각 전후
5분과 현재 environment, 클릭한 ERROR/WARN series를 Discover에 넘기고
`message`/`level`/`errorCode`/`path`/`exceptionType` 열을 연다. 링크에는 원문 로그를 넣지 않는다.

Grafana는 prod ALB의 `grafana.laimory.app` host 규칙으로 노출한다(#368). 브라우저 로그인은
Grafana 자체 Google OAuth이며 `[auth.google] allow_sign_up=false`라 미리 등록된 Grafana 사용자
이메일만 로그인된다. admin Basic 인증은 alert 배포기 등 localhost 자동화용이다 — 외부(ALB) 경로의
`POST /login`·`Authorization: Basic` 차단(#368 A13)은 아직 적용 전이라, 그전까지는 공유 admin
비밀번호로도 외부 로그인이 가능하다.
Prometheus target file의 실제 IP와 적용 상태는 live host가
소유하며 현재 repository 상태만으로 rollout 완료를 의미하지 않는다.

Grafana admin username의 repository 기본값은 `laimory`이며 compose 최초 생성과 alert provisioning
reload가 같은 값을 사용한다. Grafana admin/encryption key, Elasticsearch API key, Discord webhook,
Google OAuth client secret, MySQL/Redis exporter credential은 Git/S3에 두지 않는다. host의 일곱 UID별 `0400` secret
file 중 하나라도 비거나 owner/mode가 다르면 systemd가 fail-closed하고, 비밀이 필요 없는
Prometheus/blackbox만 먼저 기동할 수 있다.
alert rule은 manifest가 소유하는 책임별 file-provisioning YAML로 관리하며 live EC2에서 직접 편집하지
않는다. commit SHA별 immutable S3 release는 conditional create로만 쓰고 checksum manifest를 마지막에
publish하며, 같은 SHA 재시도는 기존 bytes가 같을 때만 성공한다. host deployer는 root-only backup,
파일 집합·UID 검증, hot reload와 provisioning API의 expected UID 확인을 수행하고, release 도구는
성공 후에만 active 경로로 승격한다. rollback은 alerting 디렉터리의 Grafana-readable `0755` mode를
보존하면서 파일만 복구한다. release 사이에서 사라진 UID는 임시 `deleteRules`로 Grafana DB에서도
지우며 reload 또는 UID 확인 실패 시 이전 파일과 새 UID를 함께 복구한다.
rule은 환경 중립과 환경 고정 둘로 나뉘고, 기준은 **그 rule이 읽는 시계열이 환경마다 존재하는지**다.
환경 중립 rule(5xx 비율, p95 지연, JVM heap, Hikari, target down, host memory, filesystem,
OOM kill, PROCESSING stuck)은 PromQL에 `environment` 셀렉터를 두지 않고 집계 `by (...)`와 조인
`on (...)`에 `environment`를 넣어 환경마다 별개 alert instance를 만들며, `environment` 라벨을
선언하지 않는다 — Grafana가 조건 쿼리 결과의 라벨을 alert instance 라벨로 넘기므로 그대로 흐르고,
커스텀 라벨을 두면 쿼리 라벨을 덮어써 다른 환경의 알림이 오표기된다. 나머지 rule은 그 환경에만 있는
자산(dev/monitoring 전용 exporter·수집기, 공개 도메인 probe, 환경 공유 자산인 Elasticsearch의
monitoring host 수집기를 읽는 `laimory_elasticsearch_unhealthy`)을 읽으므로 `environment="dev"`를
유지한다. log pipeline 계열은 환경 중립이다 — Filebeat stats 수집기는 dev·prod WAS 전체에 설치되고,
`laimory_application_error_log`는 wildcard index(`laimory-*`)를 environment terms(마지막 date
histogram 앞)로 나눠 환경별 alert instance를 만든다. `or`로 분기를 잇는 rule은 각 분기를
`((1 - metric) > 0)`처럼 필터링해야 한다 — 필터 없는 선행 분기의 값 0 시계열이 동일 라벨셋의 후행
staleness 분기를 중복 제거로 가리고, `metric == 0` 필터는 값 0이라 threshold(>0)를 넘지 못한다.
`backup-rules.yml`의 백업 신선도 rule 2종(prod MySQL mysqldump·EBS snapshot의 26h staleness)은 각각
prod MySQL host와 monitoring host의 backup timer가 쓰는 textfile 시계열 하나씩만 읽는 환경 고정
rule이다 — 백업 체계 자체의 계약은 `deploy/monitoring/README.md`의 "prod MySQL backup"이 소유한다.
notification policy의 `group_by`는 `environment`를 포함해 환경별로 알림 그룹을 나눈다.
`notification-policy.yml`·`templates.yml`·`contact-points.yml`은 alert rule 자동 배포 workflow의
대상이 아니므로 merge만으로 반영되지 않고 monitoring host에서 수동 반영과 reload가 필요하다.
일반 host memory는
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

`TrustedEdgeRequestFilter`는 socket peer로 엣지를 판정하고, 엣지가 둘인 전환기라 두 계약을 동시에
지원한다(#327). 신뢰 대역 `app.edge.trusted-proxy-cidrs`(env `APP_EDGE_TRUSTED_PROXY_CIDRS`)를 먼저
평가하고, 매칭되지 않으면 loopback 분기를 본다 — 운영에서 두 집합은 서로소다. 설정한 CIDR이 malformed면
기동에 실패한다.

- **ALB 엣지** — peer가 신뢰 CIDR(ALB ENI가 사는 퍼블릭 서브넷) 안이면 `X-Forwarded-For` **최우측**
  값을 client IP로 쓴다. ALB가 자신이 관찰한 TCP peer를 오른쪽에 append하므로 클라이언트가 미리 넣은
  위조 값은 전부 왼쪽에 쌓인다(최좌측을 쓰면 위조가 그대로 통과한다). 최우측이 valid literal이 아니면
  왼쪽으로 되돌아가지 않고 socket address로 fallback한다. header line이 여러 개면 마지막 line의
  마지막 element만 본다. ALB는 임의 이름의 custom header를 덮어쓰지 못하므로 이 엣지에서
  `Laimory-Client-IP`는 신뢰하지 않는다.
- **loopback 엣지**(#327 nginx 전환기의 잔재 코드 경로 — dev가 ALB 직결로 전환(#369)돼 배포 환경
  트래픽은 더 이상 타지 않는다. 코드 경로 제거는 후속 정리 후보) — peer가 정확히 `127.0.0.1`이고
  `Laimory-Client-IP` header가 정확히 하나의 valid IPv4/IPv6 literal일 때만 이를 normalize해
  downstream `request.getRemoteAddr()`로 노출한다. repeated/comma/malformed/missing
  header는 socket address로 fallback하며 이 엣지에서는 XFF와 User-Agent를 IP 결정에 쓰지 않는다.

rejected 원문은 어느 엣지에서도 기록하지 않는다. checked-in 기본 신뢰 대역은 비어 있어(=ALB 엣지 없음)
설정이 없는 환경은 기존 loopback 계약만 갖는다. 실제 대역은 배포 환경 `.env`가 소유한다.

두 엣지 모두 단일 `X-Forwarded-Proto: https|http`만 scheme/secure/serverPort view를 변환해 OAuth HTTPS
redirect와 Secure cookie를 보존한다. AI 서버 등 사설망에서 애플리케이션 8080으로 직접 접근하는 peer가
보낸 custom IP/XFP/XFF는 신뢰 대역 밖이면 모두 무시한다 — 신뢰 대역을 넓게 잡으면 그 대역의 peer가
XFF를 위조할 수 있으므로 대역은 ALB ENI가 사는 서브넷으로 제한하고 8080 인바운드를 SG로 좁힌다.
`server.forward-headers-strategy=none`은 유지한다 — Spring `ForwardedHeaderFilter`는 필터 순서가
충돌하고 XFF **최좌측**(위조 가능)을 remote address로 쓴다. access log `clientIp`는 trusted-edge
filter 다음의 `TransactionIdFilter`가 보는 `request.getRemoteAddr()`다.

## Health Signals

- `/status`: plain JSON DB connection probe
- `/api/v1/intro`: dev deploy health gate, DB와 app config row 확인
- `/readyz`: ALB 타깃그룹 헬스체크용 — actuator readiness 그룹(`readinessState`·`db`·`redis`만,
  diskSpace·ping 제외)의 메인 포트 additional-path. 별도 관리 컨텍스트는 메인 앱이 연결을 못
  받아도 UP일 수 있으므로 실제 트래픽 포트(8080)를 검사한다.

`/status`·`/api/v1/intro`는 Redis readiness를 포괄하지 않는다. Kakao·S3는 어느 signal도 확인하지 않는다.

## Invariants

- transaction ID는 response header 하나로만 노출하고 envelope에 중복하지 않는다.
- request URI에는 query string을 붙이지 않는다.
- app log field/index 변경은 Filebeat, template, ILM과 Kibana search까지 함께 확인한다.

## Known Gaps

- alert rule 파일은 관련 `dev` merge에서 자동 rollout되지만 dashboard·collector·target·contact point 등
  다른 provisioning 자산은 여전히 live rollout 완료를 뜻하지 않는다. SSM identity/secret 구성,
  Discord firing/resolved와 24시간 soak도 별도로 확인한다.
- distributed tracing과 dependency-complete readiness endpoint는 없다.
- **로그 경보도 환경 중립이다(#348).** 지표 기반 rule 9개에 더해 log pipeline 계열과 ERROR 로그
  경보가 prod를 평가한다. Grafana ES datasource는 `laimory-*` wildcard이고 API key도 `laimory-*`
  범위다. 단, datasource·dashboard json은 자동 배포 대상이 아니라 host 수동 적용이 필요하고,
  Filebeat self-metric 수집기는 prod WAS 2대 설치가 전제다(설치 전에 rule이 먼저 배포되면
  수집기-부재 분기가 오발화한다).
- **개인정보 게이트는 닫히지 않았다.** prod 로그 수집이 켜졌으므로 트래픽이 생기는 순간부터 접속 IP와
  요청 본문 일부가 7일 보존된다. 지금은 사용자가 없어 실질 데이터가 없을 뿐이고, 개인정보처리방침
  개정·데이터 소유자 승인은 이 문서 상단 절차대로 공개 전에 끝내야 한다.

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
