---
schema_version: 1
status: merge-candidate
pr_number: 150
pr_url: https://github.com/soma17th-369/Laimory-server/pull/150
title: "feat: DNS 가비아→Route53 이관 반영 — dns.tf 재도입·runbook 갱신"
base_branch: dev
head_branch: feat/route53-dns
implementation_head_sha: c6b95734c93afe74f7b89334fecd51039ac62f28
generated_at: 2026-07-15T03:38:09Z
linked_issues: [112]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #150 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: laimory.app DNS 권한을 가비아 → Route53으로 이관(#112)한 결과를 코드(레시피)와 runbook에 반영한다. 라이브 이관(존 생성·NS 위임·검증)은 2026-07-14 PR 밖에서 수동 완료됐다.
- Acceptance criteria: NS가 awsdns로 위임·전파, dev.laimory.app 정상 resolve + HTTPS 200, `dns.tf`가 레시피로 복원, README runbook이 실제 절차(Route53 위임)와 일치.
- Out of scope: 라이브 인프라에 대한 `terraform apply`(레시피 모드 — 존은 콘솔/CLI 수동 생성, state 밖), certbot 갱신 dry-run 실행, 가비아 존의 옛 A 레코드 정리, prod(apex) A 레코드 라이브 생성.

## Change Summary

- `terraform/dns.tf` 재도입: `aws_route53_zone.main`(laimory.app) + 환경별 A 레코드(dev/prod → WAS EIP). PR #122에서 "미사용"으로 제거했던 코드의 복원이며, 주석에 "존 재생성 시 NS 4개가 바뀌므로 가비아 재위임 필요"를 명시.
- `terraform/outputs.tf`: `route53_name_servers` output 복원, `api_domains` 설명에서 "가비아 관리" 문구 제거.
- `terraform/variables.tf`: `api_domains` 설명을 Route53 기준으로 환원.
- `terraform/README.md`: 도메인/TLS runbook을 "가비아 A 레코드 수동" → "Route53 존 + 가비아 NS 위임" 절차로 갱신. nuke 복구 섹션에 NS 재위임 필수 명시. 라이브 존에는 dev 레코드만 있다는 의도된 드리프트 기록.
- 라이브 이관 내역(코드 밖): hosted zone `Z01241131R6FAR8U02OUW` 생성, `dev.laimory.app A → 43.203.96.33`(TTL 300), 가비아 네임서버를 AWS NS 4개로 변경. 도메인 등록(소유)은 가비아 유지.

## Plan Deviations

- 이슈 #112의 표준 순서 대비 단순화: 사전 인벤토리·TTL 낮추기·롤백 준비를 생략했다. 사용자 결정 — 옮길 레코드가 dev A 하나뿐이고 서비스 미출시라 다운타임 리스크를 감수("가비아에서 그냥 지우고 route53 쓰도록 하면 되는거 아니야").
- 이슈는 `terraform apply`로 존 생성을 전제했으나, 레시피 모드(infra-recipe-mode) 채택 이후라 콘솔/CLI 수동 생성으로 변경. 따라서 이슈 AC 중 "`terraform plan`이 dns.tf 관련 drift 없음"은 의도적으로 미충족(존은 state 밖).
- 이슈 작성 시점에 존재하던 `dns.tf`가 PR #122에서 제거된 상태여서, "apply해서 SSOT화"가 아니라 "코드 재도입"으로 작업 형태가 바뀌었다.

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| 착수 | 이슈가 전제한 `dns.tf`가 존재하지 않음 | confirmed (PR #122 a45f599에서 제거) | `git show a45f599^:terraform/dns.tf`로 원본 복원 후 주석 보강 | git log/show |
| AWS 접근 | CLI 자격증명 없음 + 콘솔 세션 만료로 브라우저 진행 불가 | confirmed | `~/.aws/config`의 SSO 프로필 확인 → `aws sso login --profile sandbox` 후 CLI로 전환 | sts get-caller-identity 성공 |
| 존 생성 | `--hosted-zone-config` shorthand가 쉼표 포함 Comment를 리스트로 오파싱 | confirmed | JSON 형식으로 재시도해 성공 | create-hosted-zone 응답 |
| 검증 | SSM `send-command`(certbot dry-run)가 권한 분류기에 거부됨 | confirmed | 미실행으로 남김 — HTTP-01 경로(DNS resolve·80포트 301)는 별도 검증됨 | Remaining Risks 참조 |
| 커밋 분리 | 워킹트리에 ELK 스팟 전환(#149) 작업분이 혼재 (ec2.tf·README·lock 파일) | confirmed | README는 hunk 선별 패치(`git apply --cached`)로 DNS hunk만 스테이징, ELK 파일 제외 | 커밋 c6b9573 diff에 ELK 내용 없음 |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| human (사용자) | 보수적 컷오버 절차 대신 단순 이관(인벤토리·TTL 준비 생략) | accepted | 레코드 1개·미출시 상태라 최악이 dev 도메인 수 분~수 시간 불통 |
| human (사용자) | apex(prod) A 레코드는 라이브 보류, 코드에는 유지 | accepted | prod 미가동 — 재구축 레시피에는 필요, 라이브 존에는 불필요 |
| Claude | 존 생성을 apply 대신 콘솔/CLI 수동으로 | accepted | 레시피 모드 원칙(살아있는 인프라에 apply 안 함) + 이 머신에 tfstate 없음 |
| PR reviewer | (없음 — 리뷰 스레드 0) | — | — |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| NS 위임 전파 | `.app` TLD 레지스트리 직접 dig + 8.8.8.8/1.1.1.1 NS 조회 → awsdns 4개 반환 | passed | 수동 dig (2026-07-14, 컷오버 ~1분 후) |
| dev 도메인 resolve | `dig @8.8.8.8 dev.laimory.app` → 43.203.96.33 | passed | 수동 dig |
| HTTPS/TLS | `curl https://dev.laimory.app/api/v1/intro` → 200(cert verify 0), `http://` → 301 | passed | 수동 curl |
| dns.tf 문법/참조 | `terraform validate` 미실행(로컬 바이너리 없음) — #122 제거분의 복원이며 참조 대상(`aws_eip.was`, `var.environments`, `var.api_domains`) 존재를 grep으로 확인 | not-run | c6b9573 |
| certbot 갱신 경로 | `certbot renew --dry-run` (SSM) | not-run | 권한 거부로 미실행 |
| CI build | GitHub check `build` | passed | c6b9573, actions run 29334829710 |

The final check for the digest commit is intentionally not recorded here because changing this file would create another head SHA. Verify it on the GitHub PR before merging.

## Remaining Risks

- certbot 자동 갱신 dry-run 미확인 — HTTP-01 전제 조건(DNS resolve·80포트)은 검증됐으나 리허설은 후속. 다음 SSM 접속 시 `sudo certbot renew --dry-run` 권장.
- 라이브 존은 tfstate 밖(레시피 모드 의도된 드리프트). nuke 재구축 시 새 존의 NS 4개로 가비아 재위임이 필수 — README에 기록됨.
- 가비아 존의 옛 dev A 레코드가 남아 있음(위임 이관 후 조회되지 않아 무해, 정리는 선택).
- `deploy.yml`은 dev push에 path 필터가 없어 이 PR 머지 시 dev 앱이 재배포(컨테이너 blip)됨.
- dev 머지는 `Closes #112`를 발동하지 않음 — 머지 후 이슈 수동 close 필요.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: AWS 콘솔 세션 만료(브라우저 경로 포기 → SSO CLI 전환), `create-hosted-zone` shorthand 파라미터 오파싱 1회(JSON으로 재시도), SSM `send-command` 권한 거부(certbot dry-run 미실행)

## Learning Candidates

- 레지스트라 NS 위임 컷오버는 "존+레코드 먼저 생성 → NS 나중 변경" 순서가 핵심 — runbook에 반영됨.
- AWS CLI shorthand 파라미터에 쉼표가 들어가면 리스트로 오파싱된다 — 쉼표 포함 값은 JSON 형식 사용.
- 한 워킹트리에 여러 작업이 혼재할 때 `git diff` → hunk 선별 → `git apply --cached` 패턴으로 파일 내 부분 스테이징이 가능(대화형 `add -p` 불가 환경).
