---
schema_version: 1
status: merge-candidate
pr_number: 151
pr_url: https://github.com/soma17th-369/Laimory-server/pull/151
title: "feat: dev ELK 로깅 서버를 스팟 인스턴스(persistent+stop)로 전환"
base_branch: dev
head_branch: feat/elk-spot-instance
implementation_head_sha: 2173482723a218cbc38b370d5d7c436a42e4e4c5
generated_at: 2026-07-15T03:38:22Z
linked_issues: [149]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #151 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: dev ELK 로깅 서버(`laimory-dev-elk-01`, t3.medium)를 스팟 인스턴스(persistent+stop)로 전환하는 terraform 레시피 변경. 온디맨드 24/7(월 ~$38) 대비 ~60-70% 절감(월 ~$11–15).
- Acceptance criteria: (1) 레시피가 elk를 스팟(persistent+stop)으로 정의하고 `terraform validate` 통과, (2) 라이브 dev-elk 교체 후 고정 IP(10.0.32.13)로 기동·Kibana 접속·Filebeat 유입 확인, (3) WAS Filebeat 무변경 동작. 이 PR은 (1)까지 담당하고 (2)(3)은 머지 후 라이브 반영 단계.
- Out of scope: 라이브 반영(destroy→재생성 — AWS 자격증명 있는 환경에서 별도 실행), prod 리소스, 다른 박스의 스팟 전환.

## Change Summary

- `terraform/ec2.tf` — `aws_instance.elk`에 `instance_market_options` 추가: `market_type=spot`, `spot_instance_type=persistent`, `instance_interruption_behavior=stop`. 회수 시 terminate 대신 stop(루트 EBS·고정 사설 IP 보존), 용량 복귀 시 자동 재시작. 섹션 주석에서 "평소 stop, 볼 때만 start" 운용 문구를 스팟 상시 가동으로 교체.
- `terraform/README.md` — "### 5. stop/start 운용" 섹션을 "### 5. 스팟 운용 (persistent+stop, 상시 가동)"으로 교체: 수동 stop 불가(`UnsupportedOperation`), interruption 동작, persistent 스팟 요청 좀비 재기동 주의(콘솔 terminate 시 Spot Request 수동 cancel), 온디맨드→스팟 교체 `-target` runbook, 교체 후 Kibana Data View 재생성 안내.
- 라이브 인프라는 이 PR로 변하지 않는다(레시피 모드). 스팟 여부는 launch 시점 속성이라 in-place 전환 불가 → 머지 후 기존 박스 destroy→재생성 필요.

## Plan Deviations

- No material deviation was observed within the evidence scope. (이슈 #149의 체크리스트 중 코드 반영 항목을 그대로 구현; 라이브 반영 항목은 계획대로 머지 후 단계로 남김.)

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| 구현 중 | 같은 워킹트리에서 다른 세션의 Route53 DNS 작업(`feat/route53-dns`, dns.tf 등)이 병행되어 브랜치·스테이징이 교차함 | confirmed | 본 PR 변경만 별도 git worktree에서 재구성해 커밋·푸시하고, 공유 워킹트리의 ELK 관련 unstaged 변경은 restore로 제거(diff로 제 변경만임을 확인 후) | 커밋 2173482가 ec2.tf·README.md 2개 파일만 포함(+41/−11), DNS 커밋 c6b9573과 분리됨 |
| 설계 검증 | persistent 스팟은 인스턴스만 terminate하면 요청이 살아남아 좀비 인스턴스를 재기동 — `terraform destroy` 시 위험 여부 확인 필요 | confirmed | provider 수정(hashicorp/terraform-provider-aws#41206, v5.86.0 머지)이 destroy 시 요청 취소를 처리함을 확인. 현 lock 5.100.0이라 안전. 콘솔 terminate 경로만 수동 cancel 필요 — README에 명시 | upstream issue #38142 / PR #41206(merged, milestone v5.86.0), `.terraform.lock.hcl` version 5.100.0 |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| human | "평소 stop, 볼 때만 start" 운용 대신 스팟 상시 가동 채택 | accepted | 실제로는 로그 상시 수집 때문에 24/7 가동하게 됨을 사용자가 확인 — 스팟이 온디맨드 24/7보다 저렴. 스팟은 수동 stop 불가라 두 모델은 양립 불가 |
| Claude | one-time+terminate 대신 persistent+stop 채택 | accepted | 회수 시 EBS·고정 IP 보존 + 자동 재시작으로 무인 복구. one-time은 회수 시 루트 볼륨째 소실·자동 복구 없음 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| terraform 문법·스키마 유효성 | `terraform validate` (provider 5.100.0, macOS에서 init -backend=false 후) | passed | 2173482723a218cbc38b370d5d7c436a42e4e4c5 |
| CI | GitHub check `build` | passed | https://github.com/soma17th-369/Laimory-server/actions/runs/29335035944/job/87092038362 |
| `terraform plan` (elk 1대 replace만 뜨는지) | not-run — 이 머신에 AWS 자격증명 없음 | not-run | 라이브 반영 시 `-target` plan으로 확인하도록 README runbook에 명시 |

The final check for the digest commit is intentionally not recorded here because changing this file would create another head SHA. Verify it on the GitHub PR before merging.

## Remaining Risks

- 라이브 반영 미실행: 기존 dev-elk는 여전히 온디맨드. `-target` destroy→재생성(README 5번 runbook)을 AWS 자격증명 있는 환경에서 실행해야 하며, 기존 ES 로그 데이터는 소실됨(허용 결정됨). 재생성 후 Kibana Data View 재생성 필요.
- 스팟 용량 장기 부족 시 ELK가 장시간 stop 상태로 남을 수 있음(자동 재시작은 용량 복귀 전제). 장기화되면 레시피로 온디맨드 복귀 가능.
- dev 머지는 deploy.yml에 path 필터가 없어 terraform-only 변경에도 앱 재배포(blip)가 발생함 — 알려진 동작.
- "Closes #149"는 main 머지에만 발동 — dev 머지 후 이슈 수동 close 필요.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: not observed within the evidence scope

## Learning Candidates

- 공유 워킹트리에서 세션이 병행될 수 있으므로, 커밋 전 `git status`로 자기 변경 외 흔적을 확인하고 교차 시 격리 worktree에서 커밋하는 절차가 유효했다 — 반복되면 스킬/규칙화 검토.
- persistent 스팟의 "terminate ≠ 요청 취소" 함정은 콘솔 운영 시에도 유효 — 인프라 runbook에 이미 반영됨.
