# Observability

## Scope

transaction ID, HTTP access log, 환경별 output과 dev ELK pipeline의 현재 계약을 설명한다.

## Read When

logging filter/field/level, error handling, logback, Docker logging, Filebeat/Elasticsearch/Kibana를 바꿀 때 읽는다.

## Authoritative Sources

- `TransactionIdFilter`, `TransactionIds`, `RequestLogAttributes`
- `GlobalExceptionHandler`, `logback-spring.xml`
- `.github/workflows/deploy.yml`
- `deploy/elk/*`
- `terraform/README.md`, `terraform/user_data/was.sh.tftpl`

## Current Request Tracing

- 서버가 모든 request에 새 UUIDv7 transaction ID를 만든다.
- request header의 client-provided ID는 사용하지 않는다.
- MDC key는 `transactionId`다.
- client 노출은 response header `Transaction-Id` 하나뿐이고 envelope에는 없다.
- filter가 request당 한 줄 `http_request_completed` access log를 남긴다.
- fields는 `method`, `path`, `status`, `latencyMs`, `errorCode`다.
- query string은 포함하지 않는다.
- 5xx는 ERROR, 4xx는 WARN, 나머지는 INFO다. 정상 `/status`는 DEBUG다.
- unhandled exception은 effective status 500으로 기록한다.

token, credential, presigned URL, query string과 body는 log하지 않는다.
exception handler 밖에서 같은 exception을 중복 logging하지 않는다.

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

## Health Signals

- `/status`: plain JSON DB connection probe
- `/api/v1/intro`: dev deploy health gate, DB와 app config row 확인

둘 다 Redis·Kakao·S3 readiness를 포괄하지 않는다.

## Invariants

- transaction ID는 response header 하나로만 노출하고 envelope에 중복하지 않는다.
- request URI에는 query string을 붙이지 않는다.
- app log field/index 변경은 Filebeat, template, ILM과 Kibana search까지 함께 확인한다.

## Known Gaps

metrics, distributed tracing, alerting과 dependency-complete readiness endpoint는 없다.

## Update When

ID 생성/노출, MDC/access fields·levels, sensitive logging, output format, Docker rotation, Filebeat/index/retention 또는
health signal이 바뀔 때 갱신한다.

## Validation

```bash
./gradlew test \
  --tests 'com.laimory.server.common.logging.TransactionIdFilterTest' \
  --tests 'com.laimory.server.common.error.GlobalExceptionHandlerTest' \
  --tests 'com.laimory.server.config.OpenApiConfigTest'
jq empty deploy/elk/ilm-policy.json deploy/elk/index-template.json
```
