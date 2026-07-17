---
schema_version: 1
status: merge-candidate
pr_number: 156
pr_url: https://github.com/soma17th-369/Laimory-server/pull/156
title: "feat: WAS user_data에 스왑 2GB 추가와 기존 박스 SSM runbook"
base_branch: dev
head_branch: feat/was-swapfile
implementation_head_sha: e61de8c063ada4a731cc5c8ab781d5497a4976dd
generated_at: 2026-07-17T02:35:00+09:00
linked_issues: []
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #156 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: 2026-07-17 dev-was 동결 장애(스왑 없는 t3.micro에서 apt-daily 메모리 스파이크 → 페이지 회수 라이브락, SSM 포함 박스 전체 동결)의 재발 방지책으로 라이브 박스에 SSM 수동 적용한 스왑 2GB를 재구축 레시피(user_data)에 반영한다.
- Acceptance criteria: 신규/재생성 WAS 박스가 부트스트랩 시 스왑 2GB를 자동 구성하고, 기존 박스용 SSM 수동 적용 runbook이 README에 남는다.
- Out of scope: t3.small 업사이즈(별도 결정), PackageKit mask(검토 후 기각), 이슈 등록(사용자 지시로 생략).

## Change Summary

- `terraform/user_data/was.sh.tftpl`: 부트스트랩 최상단에 스왑 2GB 생성 블록 추가(fallocate 2G, mkswap/swapon, fstab 등록). dev/prod WAS 공통.
- `terraform/README.md`: "WAS 스왑 2GB — 기존 박스는 수동 적용" runbook 섹션 추가. dev-was는 2026-07-17 SSM 적용 완료, prod-was 미적용 상태를 명시.
- 레시피 모드 원칙에 따라 apply 없음(WAS는 `ignore_changes=[user_data]`, user_data는 신규 박스 재현 전용).

## Plan Deviations

- No material deviation was observed within the evidence scope.

## Problems Encountered

No material problem was observed within the evidence scope. (장애 자체의 원인 분석·라이브 복구는 이 PR 이전에 대화에서 수행 — 재부팅 및 SSM 스왑 적용 완료, `/api/v1/intro` 200 확인.)

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| human | 스왑을 dev-only가 아닌 WAS 공통(user_data 무조건 실행)으로 반영 | accepted | 라이브락 위험은 1~2GB 박스 공통이며 dev-only 게이트는 불필요한 조건 분기만 추가 |
| human | PackageKit mask는 반영하지 않음 | rejected | 방아쇠 하나 제거일 뿐(두더지잡기), 효과 대비 드리프트 관리 비용이 큼 — 사용자 합의 |
| human | 이슈 없이 브랜치→PR→머지 진행 | accepted | 사용자 명시 지시(단순 반영 작업) |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| 스왑 구성 절차 자체의 유효성 | 라이브 dev-was에 동일 명령 SSM 실행 후 `swapon --show`·`free -h`로 2GiB 활성 확인 | passed | 대화 내 SSM command 결과 (2026-07-17) |
| CI build | GitHub Actions `build` check | passed | e61de8c0 — https://github.com/soma17th-369/Laimory-server/actions/runs/29549768691/job/87789646926 |
| user_data 스크립트 재실행 | 신규 박스 부팅 검증 | not-run | 재구축 시점에만 검증 가능(레시피 모드) |

## Remaining Risks

- prod-was는 스왑 미적용 상태로 남음(README runbook에 명시) — 동일 유형 동결 위험이 잔존.
- user_data의 스왑 블록은 신규 박스에서 아직 실행된 적 없음. fstab 중복 가드가 없으나 user_data는 최초 부팅 1회 실행이라 실사용 경로에선 중복되지 않음(README의 SSM runbook 쪽은 grep 가드 포함).
- deploy.yml path 필터 부재로 dev 머지 시 앱 재배포(컨테이너 재시작 blip) 발생.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: not observed within the evidence scope

## Learning Candidates

- 스왑 없는 소형(1~2GB) 박스는 OOM 킬이 아니라 회수 라이브락으로 죽는다 — 신규 소형 EC2 레시피에 스왑 기본 포함을 검토할 것(ELK 등 다른 박스 유형 포함).
- "인스턴스 running + 상태검사 통과 + SSM ConnectionLost + TCP accept되나 응답 0바이트" 조합은 유저스페이스 자원 고갈의 시그니처로, 콘솔 스크린샷·CloudWatch CPU·journal(-b -1) 순의 진단 절차가 유효했다.
