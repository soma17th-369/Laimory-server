# Observability

## Scope

transaction ID, HTTP access log, 환경별 output과 dev ELK pipeline의 현재 계약을 설명한다.

## Read When

logging filter/field/level, error handling, logback, Docker logging, Filebeat/Elasticsearch/Kibana를 바꿀 때 읽는다.

## Authoritative Sources

- `TransactionIdFilter`, `TransactionIds`, `RequestLogAttributes`, `HttpRequestLog`
- `GlobalExceptionHandler`, `ExceptionType`, `logback-spring.xml`
- `.github/workflows/deploy.yml`
- `deploy/elk/*`
- `terraform/README.md`, `terraform/user_data/was.sh.tftpl`

## Current Request Tracing

- 서버가 모든 request에 새 UUIDv7 transaction ID를 만든다.
- request header의 client-provided ID는 사용하지 않는다.
- MDC key는 `transactionId`다.
- client 노출은 response header `Transaction-Id` 하나뿐이고 envelope에는 없다.
- filter가 request당 한 줄 `http_request_completed` access log를 남긴다.
  예외는 `ExcludedPaths`(헬스체크·favicon 등 신호 없는 트래픽)뿐 — **정상 완료만** 생략되고,
  에러·미처리 예외는 경로와 무관하게 남는다. tx 발급·MDC는 제외와 무관하게 유지된다.
- fields는 `HttpRequestLog` record가 스키마다: `event`, `method`, `path`, `status`, `latencyMs`,
  `errorCode`(client 계약), `exceptionType`(내부 실패 사유), `errorDetail`(예외 클래스명·검증 메시지).
  field 추가는 record 한 곳이며 null field도 명시적으로 출력한다.
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

**요청·응답 값은 적극적으로 log한다. 금지는 진짜 비밀만: token, password, credential, presigned URL,
세션 값.** query string은 서명·token 채널이라 계속 제외한다. 금지 대상을 예외 메시지에도 넣지 않는다.
(요청/응답 body 캡처 구현은 미구현 — Known Gaps 참고.)

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
- ELK instance는 평소 stop하고 필요할 때 start한다.
- ELK가 멈춘 동안 backfill 가능 범위는 app container의 30 MB rotated log에 제한된다.

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

# 1) ELK 박스 start(평소 stop 운용) + SSM 접속
aws ec2 start-instances --profile sandbox --instance-ids "$(terraform -chdir=terraform output -raw elk_instance_id)"
aws ssm start-session --profile sandbox --target "$(terraform -chdir=terraform output -raw elk_instance_id)"

# 2) 박스 안에서: template PUT(이후 생성되는 index용). 비번은 secrets.auto.tfvars의 elk_elastic_password
ES=http://localhost:9200; PW='<elk_elastic_password>'
sudo aws s3 cp "s3://<backup_bucket>/bootstrap/elk/index-template.json" /home/ubuntu/elk/index-template.json
curl -sf -u "elastic:$PW" -X PUT "$ES/_index_template/laimory" \
  -H 'Content-Type: application/json' --data-binary @/home/ubuntu/elk/index-template.json

# 3) 현재 열린 index에도 _mapping PUT(template은 기존 index에 소급되지 않음) — 추가한 field만 명시
curl -sf -u "elastic:$PW" -X PUT "$ES/laimory-dev-*/_mapping" -H 'Content-Type: application/json' \
  -d '{"properties":{"exceptionType":{"type":"keyword"},"errorDetail":{"type":"keyword"}}}'

# 4) 확인: 새 field가 keyword 단일 타입인지 (text+keyword 멀티필드면 앱이 먼저 배포된 것)
curl -sf -u "elastic:$PW" "$ES/laimory-dev-*/_mapping" | grep -o '"exceptionType":{"type":"keyword"}'
```

머지 전 Kibana saved query/alert가 `message` 문자열 파싱에 의존하지 않는지 확인한다
(access log의 `message`는 record toString 형태 — field 쿼리를 쓰는 것이 계약).

## Health Signals

- `/status`: plain JSON DB connection probe
- `/api/v1/intro`: dev deploy health gate, DB와 app config row 확인

둘 다 Redis·Kakao·S3 readiness를 포괄하지 않는다.

## Invariants

- transaction ID는 response header 하나로만 노출하고 envelope에 중복하지 않는다.
- request URI에는 query string을 붙이지 않는다.
- app log field/index 변경은 Filebeat, template, ILM과 Kibana search까지 함께 확인한다.

## Known Gaps

- metrics, distributed tracing, alerting과 dependency-complete readiness endpoint는 없다.
- 요청/응답 body 캡처(로깅 정책상 허용)는 미구현 — 래퍼·크기 상한·비밀값 마스킹과 함께 별도 작업.

## Update When

ID 생성/노출, MDC/access fields·levels, sensitive logging, output format, Docker rotation, Filebeat/index/retention 또는
health signal이 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test \
  --tests 'com.laimory.server.common.logging.TransactionIdFilterTest' \
  --tests 'com.laimory.server.common.error.GlobalExceptionHandlerTest' \
  --tests 'com.laimory.server.common.error.ExceptionTypeTest' \
  --tests 'com.laimory.server.auth.security.AppChallengeFilterTest' \
  --tests 'com.laimory.server.config.OpenApiConfigTest'
jq empty deploy/elk/ilm-policy.json deploy/elk/index-template.json
```
