---
schema_version: 1
status: merge-candidate
pr_number: 173
pr_url: https://github.com/soma17th-369/Laimory-server/pull/173
title: "chore: dev DB bastion 허용 IP에 클라 개발자 IP 2개 추가"
base_branch: dev
head_branch: chore/bastion-allow-clientdev-ips
implementation_head_sha: 391f209e65d517fe0d9ef3b57d6e49f114bd099a
generated_at: 2026-07-20T16:02:40Z
linked_issues: []
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #173 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: dev DB 열람용 SSH bastion(`laimory-was-sg`, sg-0454dfaa7b4a182d2, 22번)의 허용 IP 화이트리스트에 클라이언트 개발자 회선 IP 2개를 재구축 레시피(`terraform.tfvars`)에 반영한다.
- Acceptance criteria: `bastion_ssh_allowed_cidrs`에 `59.11.224.64/32`, `58.224.80.134/32`가 추가되어 있고, 라이브 SG에도 동일 규칙이 존재한다.
- Out of scope: 라이브 SG 규칙 추가 자체(이미 AWS CLI로 수행, PR 밖 수동 변경), dev-mysql DB 유저 생성·변경, 앱 코드.

## Change Summary

- `terraform/terraform.tfvars` 한 파일, `bastion_ssh_allowed_cidrs` 리스트에 CIDR 2개 추가(1 insertion, 1 deletion).
- 외부에 보이는 앱 동작 변경은 없다. 이 변경은 nuke 복구·계정 이전 시 bastion SG가 4개 IP로 재현되도록 레시피를 동기화하는 목적이다.
- 살아있는 인프라에는 `apply`하지 않는다(인프라 레시피 모드). 라이브 SG 규칙은 이미 AWS CLI로 반영된 상태다.

## Plan Deviations

- No material deviation was observed within the evidence scope.

## Problems Encountered

No material problem was observed within the evidence scope.

## Review Decisions

No review decision changed the final design within the evidence scope. (PR 리뷰 스레드 0건.)

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| tfvars 변경이 빌드/문법 게이트를 통과하는지 | GitHub Actions `build` check | passed | check-run `build` SUCCESS @ 391f209 |
| 라이브 SG 22번 규칙에 4개 IP 존재 | `aws ec2 describe-security-group-rules` (수동) | passed | sgr-08d2c3986b27e8fc2(59.11.224.64), sgr-05852ad4adcceec45(58.224.80.134) 등 4개 확인 |

The final check for the digest commit is intentionally not recorded here because changing this file would create another head SHA. Verify it on the GitHub PR before merging.

## Remaining Risks

- 라이브 SG는 수동으로 이미 반영됨 → 코드=state=라이브 간 드리프트가 정상 상태다(레시피 모드). tfvars는 재구축용이라 이 PR 머지로 라이브에 apply되지 않는다.
- `deploy.yml`에 path 필터가 없어 dev 머지가 앱 재배포(컨테이너 blip)를 유발한다. 인지하고 머지한다.
- 클라 개발자 회선이 가정용 동적 IP면 공유기 재부팅/DHCP 만료 시 공인 IP가 바뀌어 재갱신이 필요하다.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: not observed within the evidence scope

Do not estimate counts from memory and do not include raw command output.

## Learning Candidates

- No transferable learning candidate identified within the evidence scope.
