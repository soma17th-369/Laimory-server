---
name: infra-recipe-mode
description:
  이 프로젝트(Laimory)의 인프라·Terraform·AWS 작업을 "레시피 모드"로 수행하는 운영 방침.
  Terraform을 항상 현실과 일치하는 "살아있는 거울"이 아니라 "재구축 레시피"로만 취급한다 —
  살아있는 인프라에는 `terraform apply`를 하지 않고, 일상 변경은 콘솔/SSM으로 손으로 하며,
  중요한 변경만 틈틈이 코드에 반영한다. `terraform/` 파일을 수정하거나, SG·IAM·EC2·user_data·
  네트워크 등 AWS 리소스를 바꾸거나, "인프라 변경", "terraform 수정", "aws 리소스", "apply 할까",
  "이거 코드에 반영", "state 맞추자", "드리프트", "재구축", "nuke 복구" 같은 말이 나오면 —
  사용자가 명시적으로 스킬을 부르지 않아도 — 반드시 이 스킬을 먼저 참고해 apply 여부와 반영 방식을
  정한다. 특히 dev/prod가 살아있는 상태에서 apply를 제안하기 전에 필수로 확인한다.
  (앱 코드·비즈니스 로직·테스트 변경에는 트리거하지 않는다.)
---

# 인프라 레시피 모드 (Laimory)

## 왜 이 방침인가

이 프로젝트의 AWS 계정은 **Innovation Sandbox**라 리스 만료 시 통째로 nuke된다. Terraform은
바로 이 **재구축(nuke 복구·계정 이전)을 한 방에 재현**하려고 존재한다 — 그게 검증된 유일한 밥값이다.

문제는 Terraform을 "항상 현실과 일치하는 거울"로 쓰려 할 때 생긴다. 팀은 콘솔/SSM으로 손으로
바꾸고, 로컬 state는 뒤처지고, 그 상태에서 `terraform apply`를 하면 **의도치 않은 재시작·드리프트
반영**이 튀어나온다(예: user_data 변경 → 인스턴스 stop/start, 죽은 route53 zone 생성). 이 공포의
근본 원인은 "거울"이라는 잘못된 기대다.

**해법: Terraform을 거울이 아니라 "재구축 레시피"로만 취급한다.** 아래 규칙은 전부 이 한 문장에서 나온다.

## 빠른 결정 (이것만 기억하면 됨)

`terraform apply`를 실행하거나 사용자에게 제안하려는 순간, 먼저 자문한다:

1. **대상이 지금 살아있는 인프라인가?** → **하지 않는다.** 레시피 모드임을 설명하고, 정말 특정 리소스를 꼭 반영해야 하면 `terraform apply -target=<리소스>`로 좁히되 `plan`으로 재시작·부수효과부터 확인한다(user_data 걸린 인스턴스는 apply=stop/start 다운타임).
2. **빈 state에 새 환경을 만드는 중인가?**(nuke 복구·새 계정 이전) → apply해도 된다.
3. `validate`/`plan`은 **언제든 OK**(읽기전용). plan에 뜬 드리프트는 apply 신호가 아니다.
4. 방금 손으로 바꾼 **중요한 인프라**가 있나? → 재구축 때 사라지지 않게 **코드에 반영**한다(긁어서 재생성 X).

## 규칙

### 1. 살아있는 인프라에 `terraform apply`를 하지 않는다
apply는 **오직 빈 state에 새 환경을 만들 때만** 한다(nuke 복구·새 계정 이전). 이미 돌고 있는
dev/prod 리소스에 대고 apply하지 않는다. → "apply=재시작/드리프트" 공포가 사라지고, 로컬 state가
뒤처져 있어도 신경 쓸 필요가 없다(재구축은 빈 state에서 fresh하게 시작하니까).

`terraform validate`와 `terraform plan`은 **읽기 전용이라 얼마든지 돌려도 된다** — 코드가 문법적으로
맞는지, 무엇이 달라졌는지 보는 용도. plan에 드리프트가 잔뜩 떠도 그건 "레시피가 최신인지" 참고일 뿐
apply 신호가 아니다. 사용자가 "apply 하자"고 해도, 대상이 **살아있는 인프라면 기본은 '하지 않음'**이고,
왜 안 하는지(재구축용이라는 것)를 설명한 뒤, 정말 필요하면 아래 예외로만 좁혀서 한다.

### 2. 일상 변경은 콘솔/SSM으로 손으로 한다
살아있는 박스·리소스를 바꿔야 하면 콘솔이나 SSM으로 직접 한다. 드리프트(코드≠state≠라이브)가
생기는 건 정상이다 — 거울이 아니니까. 굳이 clean plan으로 화해시키려 애쓰지 않는다.

### 3. 중요한 변경은 틈틈이 코드(레시피)에 반영한다
빠뜨리면 재구축 때 사라지는 중요한 변경(새 SG·IAM·user_data 스텝 등)은 **손으로 코드에 반영**한다.
이건 "AWS를 긁어서 코드 재생성"이 아니라 "내가 아는 변경을 코드로 옮기는" 것이다.

> ⚠️ **왜 '나중에 긁어서 재생성'이 답이 아닌가**: AWS *리소스*(VPC·SG·EC2·IAM)는 import로 긁을 수
> 있지만, **박스 안 on-host 설정**(nginx/certbot·mysql schema+유저·redis ACL·앱 .env)은 AWS API로
> 안 긁힌다. 이게 `user_data` 스크립트의 핵심 가치라, 레시피를 버리고 재생성하려 하면 제일 급한
> 순간(nuke 복구)에 on-host를 맨손으로 다시 만들어야 한다. 그래서 **레시피는 버리지 말고 업데이트**한다.

### 4. On-host 설정은 Terraform 소관이 아니다
박스 안 설정은 두 경로로만 재현한다:
- **신규 박스**: `user_data` 스크립트(최초 부팅 시 1회 실행).
- **기존 박스**: SSM으로 수동 적용(user_data는 재실행되지 않으므로).

WAS·mysql은 `lifecycle { ignore_changes = [ami, user_data] }`라 — user_data 변경은 **신규 박스
재현용**이고 apply로는 기존 박스에 안 들어간다. 그러니 새 on-host 리소스(DB 유저·SSH 유저 등)를
추가하면 **`terraform/README.md`에 SSM 수동 적용 runbook을 남긴다**(다음 운영자가 "apply가 이걸
만든다"고 오해하지 않게).

## 이 프로젝트 실무 메모

- **terraform 바이너리 미설치**: 필요하면 scratchpad에 받아서 실행
  (`https://releases.hashicorp.com/terraform/<ver>/terraform_<ver>_windows_amd64.zip`).
  backend=local, 로컬 `terraform.tfstate`, providers는 `.terraform/`에 캐시됨.
- **plan 시 `certbot_email` 필요**: secrets에 없어서 `-var="certbot_email=..."` placeholder를 넘겨야
  plan이 통과한다(was user_data는 ignore라 값은 결과에 무영향).
- **`deploy.yml`은 image/deploy 관련 path만 감시한다** → `src/main`, Gradle build/wrapper,
  Dockerfile/dockerignore, workflow 자체가 바뀔 때만 dev application을 배포한다. Terraform·skill·문서·
  monitoring-only 변경은 application container를 교체하지 않는다.
- **정말 살아있는 인프라에 반영해야 하는 예외**: `terraform apply -target=<리소스>`로 해당 리소스만
  좁혀서 하되, 재시작/부수효과를 plan으로 먼저 확인한다. user_data가 걸린 인스턴스는 apply 시
  stop/start(다운타임)임을 기억한다. **기본값은 여전히 "apply 안 함"이다.**

## 예시

**예시 1 — 살아있는 인프라에 apply 요청**
사용자: "terraform apply 해서 코드랑 실제랑 맞춰줘."
대응: 바로 apply하지 않는다. "레시피 모드라 살아있는 인프라엔 apply를 안 한다(재구축용). `plan`으로 뭐가 다른지는 보여줄 수 있고, 특정 리소스만 꼭 반영해야 하면 `-target`으로 좁혀 부수효과 확인 후 하겠다"고 설명하고 선택을 받는다.

**예시 2 — 콘솔/SSM으로 인프라를 바꾼 뒤**
사용자가 콘솔에서 SG 규칙을 열거나 SSM으로 박스 설정을 바꿨다.
대응: 라이브는 그대로 둔다(드리프트 OK). 그 변경이 재구축 때 필요하면 해당 `.tf`(예: `security_groups.tf`)·`user_data`에 **손으로 반영**해 PR을 낸다. state를 clean plan으로 맞추려고 apply하지 않는다.

## 한 줄 요약

거울이 아니라 레시피다. **apply는 재구축 때만. 일상은 손으로. 중요한 건 코드에 반영. on-host는 SSM+README 문서.**
