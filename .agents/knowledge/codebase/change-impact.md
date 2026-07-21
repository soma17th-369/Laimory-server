# Change Impact Map

## Scope

흔한 변경이 코드·schema·운영 문서와 어떤 검증으로 이어지는지 연결한다.

## Read When

구현 전 영향 범위를 잡고 구현 후 누락된 동기화가 없는지 확인할 때 읽는다.

## Authoritative Sources

실제 import·생성자·runtime 호출 경로, schema/config/workflow와 관련 tests가 권위다.

## Impact Map

| Change | Check together | Minimum validation |
|---|---|---|
| Entity, column, index, FK | Entity, `schema.sql`, repository, running DB rollout, persistence knowledge | unit + integration |
| `schema.sql` | Compose first-init, Terraform S3 bootstrap, MySQL user data, manual DDL | fresh-volume integration when needed |
| Redis key, value, TTL | store/service, `RedisGateway`, live compatibility, session namespace | Redis unit + integration |
| property or env name | both properties, deploy env/preflight/`-e`, Terraform user data, environments knowledge | context boot + targeted tests |
| auth secret name | properties, deploy preflight, WAS `.env` docs, Terraform docs | build + workflow review |
| AI staging contract | staging entities/schema, dispatcher, assembler, validator, cleanup, glossary | focused contract tests + callback integration |
| photo storage | S3 service, object key, payload, CDN, cleanup, IAM/Terraform | photo + persistence tests |
| cleanup or retention | scheduler, repositories, S3 delete ordering, properties | scheduler tests |
| response or transaction ID | filter, envelope, OpenAPI, controller/error tests, API/observability knowledge | focused MockMvc tests |
| log field or index | filter/logback, Filebeat, index template, ILM, Kibana query | logging tests + JSON validation |
| runtime dependency | `build.gradle`, Dockerfile, CI, deployment knowledge | `./gradlew build` + image build |
| deploy flow or health path | workflow, System/AppConfig API, preflight/rollback docs | build + workflow review |
| Terraform user data | `ec2.tf` lifecycle ignore, manual SSM runbook, README | fmt + validate + reviewed plan |
| test tag or task | `build.gradle`, CI, annotations, testing knowledge | test + integration task |

## Invariants

- 표는 탐색 시작점이지 변경 파일 자동 목록이 아니다. 실제 참조를 `rg`로 다시 확인한다.
- 의미가 바뀐 knowledge만 갱신하며 단순한 파일 접촉을 문서 변경 사유로 삼지 않는다.
- data/API/AI 계약 변경은 code-only 변경으로 끝내지 않는다.

## Known Gaps

자동 dependency graph나 generated reference는 없다. 현재는 code search와 focused test로 확인한다.

## Update When

새 cross-cutting dependency, rollout 단계 또는 검증 task가 생길 때 갱신한다.

## Validation

```bash
git diff --check
rg -n '<changed-symbol-or-property>' src .github terraform
```
