---
schema_version: 1
status: merge-candidate
pr_number: 141
pr_url: https://github.com/soma17th-369/Laimory-server/pull/141
title: "feat: agent knowledge와 merge-pr 스킬 추가"
base_branch: dev
head_branch: codex/agent-knowledge-merge-pr
implementation_head_sha: 843b9c488b837bb368cadf72ab14d0e0cafe5396
generated_at: 2026-07-12T15:43:28+09:00
linked_issues:
  - 140
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #141 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: coding agent의 공통 규칙과 코드베이스 knowledge를 분리하고, 검증 가능한 PR digest 기반 squash merge workflow를 저장소 skill로 제공한다.
- Acceptance criteria: `AGENTS.md`가 단일 rule/router이고 `CLAUDE.md`가 이를 가리키며, 현재 코드의 domain·API·AI·auth·persistence·operations 지식이 분리된다. 명시적 merge 요청만 Ready 전환과 merge를 허용하고 모든 gate를 통과해야 한다.
- Out of scope: `/a/api` JWT enforcement 구현, production AI adapter, database migration framework 도입, Terraform live apply.

## Change Summary

- `.agents/knowledge/`에 domain language와 invariants, codebase 구조, runtime/interface/data/operations 문서를 추가하고 branch·commit 규칙을 이동했다.
- `AGENTS.md`를 공통 rule과 knowledge router로 축소하고 `CLAUDE.md`를 symlink로 전환했다.
- AI write-then-notify와 server-internal `itemIds`, `/a/api` intended/current/fallback/gap을 현재 코드에 맞게 문서화하고 stale Javadoc·Terraform 설명을 교정했다.
- PR 상태, review threads, 필수 build와 head SHA를 검사하는 read-only helper, digest template과 `merge-pr` skill을 추가했다.
- 명시적 merge 요청이 OPEN Draft를 Ready로 전환할 수 있도록 workflow를 보완하고, inspector에는 Draft blocker를 방어선으로 유지했다.

## Plan Deviations

- 초기 knowledge 범위에 runtime authentication, AI contract, change-impact와 environment matrix가 추가됐다. 코드 대조와 plan review에서 반복적으로 필요한 현재 상태·known gap이 확인됐기 때문이다.
- 별도 작업이던 issue #140의 `merge-pr` skill을 사용자 요청에 따라 knowledge migration과 같은 PR로 통합했다.
- 실제 첫 merge 시도에서 Draft가 blocker가 되어, 사용자 승인 후 Ready 전환 절차와 방어 테스트를 merge 전에 같은 PR에 추가했다.

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| GitHub authentication check | sandbox의 일반 `gh auth status`가 로그인 상태를 읽지 못해 게시가 일시 중단됐다. | confirmed | macOS Keychain 접근이 가능한 실행 권한에서 인증을 다시 확인했다. | authenticated push와 PR 생성 성공 |
| PR creation | GitHub App connector가 이 저장소에서 PR 생성 권한을 갖지 않아 요청이 거부됐다. | confirmed | authenticated `gh pr create` fallback으로 Draft PR을 생성했다. | PR #141 생성 |
| First merge inspection | 다른 gate는 통과했지만 PR이 Draft라 merge workflow가 중단됐다. | confirmed | 명시적 merge 요청의 Ready 전환 절차를 skill에 추가하고 PR을 Ready로 바꿨다. | implementation head `843b9c4`, helper tests 16개 통과 |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| Human | Claude와 Codex의 구현 규칙을 한 파일에 둔다. | accepted | `AGENTS.md`를 canonical rule/router로 두고 `CLAUDE.md`는 symlink로 만들었다. |
| Plan review | callback body에서 `itemIds`를 제거하되 내부 DTO 역할은 유지한다. | accepted | assembler와 validator가 staging association으로 조립한 `itemIds`를 계속 사용한다. |
| Plan review | `/a/api` intended contract와 현재 enforcement를 분리한다. | accepted | bearer contract, current `permitAll`, userId 0 fallback과 enforcement gap을 각각 기록했다. |
| Skill validation | merge action의 암묵 실행을 허용하지 않는다. | accepted | OpenAI agent policy의 implicit invocation을 끄고 body에서 explicit merge authorization을 재검증한다. |
| Human | explicit merge 요청이면 Draft를 해제하고 같은 workflow를 계속한다. | accepted | Ready 전환 후 상태를 재확인하는 단계와 inspector Draft 방어 테스트를 추가했다. |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| Java unit·slice·architecture rules | `./gradlew test` | passed | implementation head ancestry `843b9c4` |
| merge gate evaluator | Python unit tests 16개 | passed | `843b9c4` |
| skill structure and invocation policy | YAML/frontmatter·agent policy 검사 | passed | `843b9c4` |
| knowledge navigation | relative Markdown link, old path, secret/raw-note checks | passed | current conversation and implementation diff |
| local operations config | Docker Compose config와 ELK JSON validation | passed | current conversation |
| GitHub merge gates before digest | inspector: clean, head match, no conflict/thread blocker, `build=SUCCESS` | passed | GitHub Actions run for `843b9c4` |
| Terraform static validation | `terraform fmt -check`, `terraform validate` | not-run | local Terraform CLI unavailable |

The final check for the digest commit is intentionally not recorded here because changing this file would create another head SHA. Verify it on the GitHub PR before merging.

## Remaining Risks

- Terraform CLI가 없어 Terraform format/validate는 로컬에서 확인하지 못했다. 변경은 README와 output description에 한정된다.
- Spring integration tests는 실행하지 않았다. runtime Java 변경은 동작 코드가 아닌 Javadoc 교정에 한정된다.
- 공식 skill `quick_validate.py`는 local PyYAML 부재로 실행되지 않았고, 동일 구조 조건을 별도 YAML 검사와 16개 helper test로 확인했다.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: GitHub authentication context mismatch, connector PR creation permission, initial Draft merge gate

## Learning Candidates

- macOS Keychain 기반 `gh` 인증은 sandbox 밖 권한에서 확인해야 하는 실행 환경 차이를 운영 지침으로 승격할지 검토한다.
- 명시적 merge 요청의 Draft→Ready 전환은 이번 skill update로 채택했으며, inspector의 Draft blocker는 전환 실패 방어선으로 유지한다.
