# TIMELINE AI 접수 대기열 켜기·끄기 (#515)

## 배경

AgentCore 새 세션 생성 한도(계정·리전 25 TPS)를 두 인스턴스 합산으로 지키려고 TIMELINE AI 접수를 SQS 대기열 뒤로 옮길 수
있게 했다. 기본값은 지금 동작(요청 스레드에서 바로 접수)이고, 아래 env를 넣어야 대기열을 쓴다. 동작 설명은
[timeline-draft runtime](../../.agents/knowledge/codebase/runtime/timeline-draft.md) "접수 대기열 경로"가 소유한다.

이 문서는 dev 활성화 절차를 다룬다. test 환경 부하 검증(어느 1초 25건 이하)은
[타임라인 부하 테스트 runbook](../../load-tests/timeline-draft/README.md) "접수 대기열 송신률 검증"에 있다.
prod 절차·지표·경보는 아직 없다(#515 후속).

## env 값

모두 host `/home/ubuntu/app/.env`에 **한 줄씩만** 쓴다. `--env-file`은 같은 key가 두 번 있으면 조용히 마지막 값을 쓰고,
배포 preflight는 아직 이 key들을 검사하지 않는다. 잘못된 값은 앱 기동 검증이 막는다(컨테이너가 뜨지 않는다).

| key | dev에 넣을 값 | 없을 때(기본) | 허용 값과 기동 실패 조건 | 효과 |
|---|---|---|---|---|
| `APP_AI_DISPATCH_QUEUE_TIMELINE_URL` | `https://sqs.ap-northeast-2.amazonaws.com/<계정 ID>/laimory-dev-ai-dispatch-timeline` (SQS 콘솔의 URL 그대로) | 빈 값 | route=`queue` 또는 worker on이면 필수. `https://sqs.<region>.amazonaws.com/<12자리>/<큐 이름>` 형식이 아니거나 region이 `AWS_REGION`(기본 `ap-northeast-2`)과 다르면 기동 실패 | 적재·수신할 큐 |
| `APP_AI_DISPATCH_WORKER_ENABLED` | `true` | `false` | `true`·`false`(대소문자 무시). `true`인데 URL이 없으면 기동 실패 | 이 인스턴스가 큐를 소비한다(poller 1개 + 접수 slot) |
| `APP_AI_DISPATCH_TIMELINE_ROUTE` | `queue` (worker를 켠 **뒤에**) | `inline` | `inline`·`queue`(대소문자 무시). 다른 값은 기동 실패 | draft POST가 큐에 적재한다 |
| `APP_AI_DISPATCH_LIMITER_PERMITS` | `1` (prod와 같은 AWS 계정이면). 다른 계정이면 넣지 않는다 | `5` | 1 이상의 정수. 0·음수·숫자 아님은 기동 실패 | 250ms 창당 세션 생성 허가 수. 어느 1초든 최대 5배(1이면 5건/초) |
| `APP_AI_DISPATCH_WORKER_SLOTS` | 넣지 않는다 | `16` | 1 이상의 정수 | 동시에 접수 중일 수 있는 메시지 수 |

함께 확인할 기존 key:

- `APP_AI_MODE`: 대기열 검증은 `fake`로 한다. worker도 이 값의 dispatcher를 부르므로 `fake`면 실 AI를 호출하지 않는다.
  `agentcore`로 바꾸면 실 AgentCore가 호출된다.
- `AWS_REGION`: 없거나 `ap-northeast-2`여야 한다(기존 preflight 계약). 큐 URL region과 같아야 한다.
- AWS credential·profile·endpoint override key는 넣지 않는다(기존 preflight가 금지). 자격증명은 EC2 인스턴스 프로파일이다.

`APP_AI_DISPATCH_LIMITER_PERMITS`는 route와 무관하게 **User Memory 04:30 배치에도 적용된다**. 배치도 접수마다 같은 limiter
허가를 받기 때문이다. dev 규모에서는 1이어도 배치에 영향이 없다.

## 사전 준비

1. 이 기능이 든 버전이 dev에 배포돼 있다(플래그를 넣기 전에는 동작이 바뀌지 않는다).
2. dev 큐 `laimory-dev-ai-dispatch-timeline`이 있다(visibility 60s — 처리권 TTL 55s와 맞물린다, 보관 기간 10분).
   dead-letter 큐는 두지 않는다.
3. dev WAS 인스턴스 프로파일 role에 아래 권한이 있다.

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility"],
    "Resource": "arn:aws:sqs:ap-northeast-2:<계정 ID>:laimory-dev-ai-dispatch-timeline"
  }]
}
```

## 켜기

env를 바꾼 뒤에는 컨테이너를 **재생성**해야 반영된다(`docker restart`는 옛 env를 유지한다). dev host에서
`sudo load-tests/timeline-draft/scripts/dev-recreate.sh`로 재생성한다. 이 스크립트는 중복 key를 막고, 새 컨테이너가 뜨지
않으면 이전 컨테이너로 되돌린다. 단 `APP_AI_MODE=agentcore`면 실행을 거부하므로 그때는 dev 배포 workflow를 다시 실행한다.

1. `.env`를 백업한다(`cp .env .env.before-515`).
2. **worker 먼저**: `APP_AI_DISPATCH_QUEUE_TIMELINE_URL`, `APP_AI_DISPATCH_WORKER_ENABLED=true`,
   (같은 계정이면) `APP_AI_DISPATCH_LIMITER_PERMITS=1`을 넣고 재생성한다.
   - 로그 `AI 접수 대기열 worker 시작: slots=16`이 보여야 한다.
   - `AI 접수 대기열 수신 실패`가 1초마다 반복되면 IAM 권한이나 URL 문제다. 메시지의 오류 코드(`AccessDenied` 등)를 본다.
3. **그다음 적재**: `APP_AI_DISPATCH_TIMELINE_ROUTE=queue`를 넣고 재생성한다.
4. 앱에서 타임라인을 하나 만든다.
   - 로그 `timeline 대기열 접수 완료: taskId=…` 뒤 `fake AI callback sent`가 보이고 폴링이 SUCCESS가 된다.
   - SQS 콘솔에서 본 큐의 available·in flight가 0으로 돌아온다.

worker보다 route를 먼저 켜면 메시지가 쌓이기만 하고, 1분(접수 대기 예산)을 넘긴 task는 worker가 켜진 뒤
FAILED(`-1009`)·환불로 끝난다.

## 확인할 시나리오(dev, `fake`)

| 시나리오 | 방법 | 기대 결과 |
|---|---|---|
| 정상 | 앱에서 생성 | 위 "켜기" 4번 |
| 중복 전달 | SQS 콘솔에서 방금 처리된 task의 메시지를 본 큐로 다시 보낸다(본문 예시는 표 아래) | `timeline 대기열 메시지 건너뜀(이미 접수·종결·만료)`, AI 재호출 없음 |
| 대기 예산 초과 | `APP_AI_DISPATCH_WORKER_ENABLED=false`(route는 queue)로 재생성 → 생성 → 1분 넘게 기다림 → worker를 다시 켜 재생성 | `timeline 접수 대기 예산 초과(접수하지 않고 종결)`, 폴링 FAILED(`-1009`), 크레딧 환불 |
| 해석 불가 메시지 | 본 큐에 `not json`을 보낸다 | `timeline 대기열 메시지 해석 실패(삭제한다)`가 한 번 찍히고 메시지가 사라진다 |
| 되돌리기 | 아래 "끄기" | 다시 요청 스레드에서 바로 접수 |

중복 전달 본문 — `taskId`는 `timeline 대기열 접수 완료` 로그의 값이다. worker는 task의 처리 단계를 먼저 보므로
`dailyRecordId`·`window`는 형식만 맞으면 된다.

```json
{"requestType":"TIMELINE","taskId":"<로그의 taskId>","dailyRecordId":1,"window":{"startAt":"2026-11-21T00:00:00+09:00","endAt":"2026-11-22T00:00:00+09:00"}}
```

worker를 오래 꺼 두면 4분(큐 경로 처리 기한) 뒤 유실 처리가 FAILED(`-1008`)·환불로 끝낸다.

## 끄기(rollback)

1. `APP_AI_DISPATCH_TIMELINE_ROUTE=inline`으로 바꾸거나 줄을 지우고 재생성한다. 새 draft는 바로 접수된다.
2. SQS 콘솔에서 본 큐의 available·in flight가 0이 되고 6분(큐 경로 task 수명)이 지나기를 기다린다.
3. `APP_AI_DISPATCH_WORKER_ENABLED`를 지우고 재생성한다. 큐 URL과 limiter 값은 남겨도 동작에 영향이 없다.

이 기능 이전 버전으로 코드를 되돌리는 것은 2번이 끝난 뒤에만 한다. 구버전은 `DISPATCH_PENDING` task를 읽지 못해 폴링이
500이 된다.

## 주요 로그

| 로그 | 뜻 |
|---|---|
| `AI 접수 대기열 worker 시작` / `종료` | worker lifecycle |
| `AI 접수 대기열 수신 실패` | ReceiveMessage 실패(권한·URL·네트워크). 1초 뒤 재시도 |
| `timeline 대기열 접수 완료` | AI 접수 확인 |
| `timeline ai dispatch enqueue rejected (미적재 확정)` | SendMessage 4xx — draft POST 502(`-1009`)·환불 |
| `timeline ai dispatch enqueue outcome unknown` | SendMessage 결과 불명 — 202, 미적재면 유실 타이머가 종결 |
| `timeline 접수 대기 예산 안에 세션 생성 허가를 받지 못함` | 접수 대기 예산(60s) 안에 허가를 못 받음 — 접수하지 않고 FAILED(`-1009`)·환불 |
| `timeline 세션 생성 허가 확인 실패` | limiter Redis 오류 — 접수하지 않고 FAILED(`-1009`)·환불 |
| `AI 접수 throttle 재시도` | AgentCore 용량 한도 거절 뒤 재시도 |
| `timeline ai dispatch rejected (미접수 확정)` | AI 거절 — FAILED(`-1009`)·환불 |
| `timeline ai dispatch outcome unknown, keeping PROCESSING` | 접수 불명 — 재전송 없음 |
