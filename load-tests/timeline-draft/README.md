# 타임라인 생성 부하 테스트 (#251)

Issue [#251](https://github.com/soma17th-369/Laimory-server/issues/251)의 k6 부하 테스트 자산이다.
"서로 다른 사용자 1,000명이 1초 이내에 `POST /a/api/v1/timeline/drafts`를 각 1회 요청하는 one-shot spike"를
재현 가능한 형태로 실행하고, 처리량·응답시간·오류율과 병목을 기록한다.

순수 접수 경로와 동기 geo 경로를 한 결과에 섞지 않는다. 두 결과를 분리해야 "느린 원인이 WAS/DB인지
Kakao WebClient pool인지"를 구분할 수 있다.

## 안전 경계

- 실제 Kakao Local API를 호출하지 않는다. geo 부하는 [#257 simulator](../kakao-simulator/README.md)만 쓴다.
- 실제 AI service로 dispatch가 전파되지 않게 `APP_AI_MODE=noop`을 확인하고 실행한다.
  localhost 외 대상에는 `CONFIRM_AI_NOOP=yes` 없이 k6가 실행되지 않는다.
- 실제 사용자 데이터를 읽거나 쓰지 않는다. 사용자·좌표·주소·rawId는 전부 합성값이다.
- access token, user manifest, k6 raw 결과는 `.artifacts/`에만 두고 커밋하지 않는다.
- dev WAS `.env` 변경, container recreate, simulator EC2 조작과 원복은 **사용자가 직접 수행한다**.
  이 저장소의 스크립트는 어떤 AWS 리소스도 만들거나 바꾸지 않는다.

## 산출물

```text
load-tests/timeline-draft/
├── README.md                       # 이 runbook
├── .gitignore                      # .artifacts/ 격리(anchored)
├── .artifacts/.gitkeep             # 격리 sentinel — 나머지는 전부 무시된다
├── k6/
│   ├── calendar-core.js            # 접수 경로만(최소 사례)
│   ├── mixed-day.js                # 실측 분포 68행, 좌표 없음(DB 스케일링)
│   ├── geo-day.js                  # 실측 분포 + 실제 좌표 37개(simulator 필수)
│   └── lib/{config,payload,tokens,spike}.js
├── scripts/
│   ├── generate-tokens.py          # user_id 목록 → access token(JWT HS256)
│   ├── run-ladder.sh               # 단계 사다리 + 중단 gate
│   ├── dev-recreate.sh             # .env 수정분 반영(컨테이너 재생성, dev host에서 실행)
│   ├── verify-artifact-hygiene.sh  # artifact 격리·secret 누출 검증
│   └── verify-redis-residue.sh     # Redis 잔여 확인
└── sql/
    ├── 01-seed-users.sql           # 합성 사용자 1,000명
    ├── 02-export-user-ids.sql      # user_id 목록 추출
    ├── 03-db-size-baseline.sql     # DB 규모·buffer pool 기준선
    ├── 04-verify-run.sql           # run 결과 검증(고유 사용자·task 수)
    ├── 05-cleanup-dry-run.sql      # 삭제 예정 행 수
    ├── 06-cleanup.sql              # 실제 삭제
    └── 07-verify-residue.sql       # 잔여 0 검증
```

## 선행 조건

- k6 (검증 시점 `v2.1.0`), Python 3, `mysql` client, `redis-cli`, `git`
- dev MySQL과 shared Redis 접근 경로(WAS SSH 터널 또는 SSM)
- dev `JWT_SECRET` 값(토큰 발급용, 파일이나 환경변수로만 다룬다)
- geo 시나리오에만: #257 simulator가 기동돼 있고 contract/preflight를 통과한 상태

## 시나리오

| 시나리오 | 요청 body | Kakao 호출 | 측정 대상 |
|---|---|---|---|
| `calendar-core` | CALENDAR 1개(좌표 없음) | 0 | JWT, WAS, MySQL, Redis, AI noop 접수 — 최소 사례(용량 상한 측정) |
| `mixed-day` | 실측 하루 분포 68개(일정 2·알림 41·체류/이동 크기 대역 25, 좌표 없음) | 0 | 대표 payload의 DB 쓰기 스케일링 — 커넥션 점유·풀 사이징 근거 |
| `geo-day` | 실측 분포 68개 — 실제 STAY 13·MOVEMENT 12(고유 좌표 37) + 알림 41·일정 2 | 요청당 74 | 실환경 하루치의 지오코딩 경로 전체(WebClient pool/pending, timeout/retry/circuit, servlet worker 대기) |

`mixed-day`의 분포는 실사용 하루 기록(2026-07-31: 이동 12·체류 13·알림 41·일정 2)에서 왔다.
이동·체류는 좌표가 필수라 그대로 보내면 지오코딩 경로를 타므로, enrich 후 저장 payload와 비슷한 JSON
크기의 NOTIFICATION 대역으로 대체한다 — DB 쓰기 비용은 행 수·payload 크기가 결정하고 item_type
문자열은 무관하다. 지오코딩 포함 실측은 simulator 단계에서 한다.

CALENDAR·NOTIFICATION이 Kakao를 호출하지 않는 근거는 서버 구현이다. 지오코딩 대상 좌표는 STAY 좌표와 MOVEMENT
start/end에서만 수집하고, 수집 결과가 비면 조회 자체를 생략한다.

좌표 18개는 서버 공개 상한(`app.geo.max-unique-coordinates`, 기본 30) 아래이면서 요청 하나로 전용
connection pool(기본 20)을 거의 채우는 값이다.

## 측정 설계

**barrier로 동시성을 만든다.** VU마다 iteration 1회(`per-vu-iterations`)이고, 모든 VU는 `setup()`이 정한
같은 절대 시각까지 기다렸다가 동시에 발사한다. barrier가 없으면 VU 기동 순서가 그대로 요청 분포가 되어
"1초 스파이크"가 아니라 완만한 ramp가 된다.

**`request_start_offset_ms`의 max가 request start window다.** 1초를 넘으면 서버 용량 문제가 아니라
부하 생성기가 부하를 만들지 못한 것이므로 그 run은 무효로 처리하고 same-region runner로 옮긴다.

**중단 gate는 전부 custom metric에 건다.** `setup()`의 `/status` preflight 요청이 내장 `http_req_*`에
섞이기 때문에 내장 metric에 gate를 걸면 측정 대상이 오염된다.

| metric | 기본 gate | 의미 |
|---|---|---|
| `draft_accepted` | `rate >= 0.99` | 202 + envelope `header.code=0` + `body.taskId` 존재 |
| `draft_req_duration` | `p(95) < 3000ms` | 접수 요청 응답시간 |
| `request_start_offset_ms` | `max < 1000ms` | 발사 분산(부하 생성기 건강도) |
| `draft_requests` | `count == VUS` | 모든 VU가 실제로 발사했는지 |

기본값은 SLO가 아니라 폭주를 멈추는 안전 상한이다. VU 1 calibration 결과를 보고 시나리오마다 조정한다.

### geo 시나리오는 `draft_accepted`만으로 판정하지 않는다

지오코딩이 일부 실패해도 서버는 draft를 거절하지 않는다. 품질 기준(고유 좌표 실패 20% 초과 또는 시간순
연속 3개)을 넘을 때만 502이고, 그 아래 실패는 **허용**되어 해당 item이 `address=null`, `places=[]`로 저장된
채 202가 나간다. 즉 `accepted 100%`인 run에도 좌표 일부가 조용히 지오코딩되지 않았을 수 있다.

geo 단계는 다음 두 값을 함께 확인해야 유효하다.

- simulator journal: `coord2address == keyword == VUS × 좌표수`, unmatched 0
  (두 endpoint의 수가 다르면 그 차이가 곧 허용된 실패다 — coord2address가 실패한 좌표는 keyword를 건너뛴다)
- `04-verify-run.sql` 4번 쿼리: `with_address == with_places == stay_items`

어긋나면 그 단계는 "부분 지오코딩 실패"로 기록하고, 수치를 gate 통과로 취급하지 않는다.

### 예상 포화 지점

전용 pool 용량은 active 20 + pending 20 = **동시 40**이고(기본 설정), 요청 하나의 병렬 조회 상한은
`app.geo.lookup-concurrency`(기본 20)다. 순간 동시 lookup 수는 `VUS × min(좌표수, 20)`이다.

`geo-day`는 요청 하나가 고유 좌표 37개를 만들고 요청별 병렬 조회 상한(`app.geo.lookup-concurrency`)이
20이라, 순간 동시 lookup은 `VUS × 20`이다. 2 VU면 40 = 전용 pool 용량(active 20 + pending 20)에 정확히
닿고, 3 VU부터 `LOCAL_REJECTED`가 예상된다. 초기 개발 중 합성 시나리오 실측(좌표 18개 요청, 로컬)에서도
같은 경계에서 2 VU 경계 실패·3 VU 502를 확인했다 — "요청당 병렬도가 버스트 크기를 정한다"는 관계다.

또한 실측 분포의 고유 좌표 37개는 공개 상한 `app.geo.max-unique-coordinates`(기본 30)를 넘는다.
geo-day 실행 전 dev `.env`에 `APP_GEO_MAX_UNIQUE_COORDINATES=40` 이상을 설정해야 하며(안 하면 외부
호출 전 400/-400 거절), 상한 자체의 제품 계약 변경은 별도 이슈로 다룬다.

사다리가 여기서 멈추는 것은 스크립트 실패가 아니라 **측정 결과**다. 멈춘 지점과 그때의 pool active/pending,
`laimory.geo.http.logical` 분류를 함께 기록한다.

## 실행 절차

모든 명령은 저장소 루트에서 실행한다.

### 0. 실행 조건 확인·기록

**대상의 `APP_AI_MODE`가 `noop`인지 먼저 확인한다.** draft 생성은 시나리오와 무관하게 매 요청 AI dispatch를
부르므로, `noop`이 아니면 요청 수만큼 실제 AI로 그대로 전파된다. `http`면 실 AI service가 1,000건을 받고,
`fake`는 dispatch마다 2초 대기 후 자기 서버로 HTTP 3콜과 타임라인 저장을 수행하는데 실행기가 기본
`applicationTaskExecutor`(스레드 8, 무제한 큐)라 뒤 단계 관측 구간까지 self-callback 부하가 번지고 정리
범위도 커진다. `noop`은 로그만 남기고 task가 PROCESSING TTL 3분으로 소멸한다.

localhost가 아닌 대상에는 k6가 `CONFIRM_AI_NOOP=yes` 없이는 실행을 거부한다. 이 확인 없이는 사다리를
시작할 수 없다.

```bash
CONFIRM_AI_NOOP=yes   # APP_AI_MODE=noop을 눈으로 확인한 뒤에만 붙인다
```

`.env`는 컨테이너 기동 시점에만 읽히므로 `docker restart`로는 반영되지 않는다. 컨테이너를 지우고
같은 옵션으로 다시 만들어야 하며, 특히 `APP_PUSH_MODE=firebase`면 credential read-only mount가 빠지면
앱이 기동에 실패한다.

`.env` 편집은 직접 하고, 반영만 `scripts/dev-recreate.sh`로 한다. 이 스크립트는 이미지·mount·CMD를
실행 중인 컨테이너에서 그대로 읽어 재현하므로 mount를 빠뜨릴 일이 없고, 새 컨테이너가 뜨지 않거나
health check가 실패하면 **이전 컨테이너를 되살린다**(앱이 내려간 채로 끝나지 않는다).
dev host 위에서 실행한다(SSM 세션 등).

```bash
# 1) 되돌릴 원본을 남긴다
sudo cp -p /home/ubuntu/app/.env /home/ubuntu/app/.env.before-loadtest

# 2) APP_AI_MODE 줄을 noop으로 고친다
sudo vi /home/ubuntu/app/.env

# 3) 반영 (수정 전 값 확인 → 재생성 → health check)
sudo ./dev-recreate.sh
```

⚠️ 편집 시 같은 key가 두 줄이 되지 않게 한다 — `--env-file`은 중복 key를 조용히 마지막 값으로 읽어
의도한 줄이 무시된다. 스크립트가 재생성 전에 중복 key와 `APP_AI_MODE` 오타를 검사해 막는다.

manifest에 남길 값:

```text
run-id
배포 commit SHA (APP_COMMIT_SHA)
WAS EC2 instance type / T3 credit mode / 시작 credit balance
BASE_URL(대상)과 k6 실행기 위치·리전
APP_AI_MODE / APP_GEO_MODE
DB 규모 기준선(03-db-size-baseline.sql 출력)
```

### 1. 합성 사용자 seed

```bash
mysql --defaults-extra-file=<config> <db> < load-tests/timeline-draft/sql/01-seed-users.sql
```

재실행해도 안전하다(이미 있는 행은 건너뛴다). 마지막 SELECT의 `min_created_at`이 `seoul_now`와 같은
대역인지 확인한다 — dev MySQL 호스트는 UTC이고 앱은 Asia/Seoul 벽시계로 저장하므로 시각을 그대로
`NOW()`로 심으면 앱 기준 9시간 과거가 된다.

### 2. user_id 목록 추출

```bash
mysql --defaults-extra-file=<config> -N -B <db> \
  < load-tests/timeline-draft/sql/02-export-user-ids.sql \
  > load-tests/timeline-draft/.artifacts/user-ids.txt
```

### 3. access token 발급

```bash
JWT_SECRET="$(cat ~/laimory-dev-jwt-secret)" \
  python3 load-tests/timeline-draft/scripts/generate-tokens.py \
    --user-ids load-tests/timeline-draft/.artifacts/user-ids.txt \
    --run-id 20260806-01
```

서버는 access token을 저장하지 않고 서명·issuer·만료만 검증하므로(stateless) 로그인 흐름을 태우지 않고
발급할 수 있다. 서버는 `exp`만 보고 발급 TTL 자체를 강제하지 않으므로 기본 2시간으로 만든다 — run이
길어지면 `--ttl-seconds`로 늘리고, 끝나면 파일을 지운다.

secret은 환경변수로만 전달한다(인자로 넘기면 프로세스 목록에 남는다).

### 4. VU 1 calibration

**배포·컨테이너 재기동 직후라면 워밍업부터 한다.** 새 JVM은 JIT가 덜 컴파일된 상태라 앱 연산 구간이
2~4배 부풀며(실측: 68행 요청의 비-geo 구간 438ms → 웜 99ms), 요청 40~80건이 지나야 풀린다. 배포
health check(`/intro` 반복)는 draft 경로를 데우지 못한다. 측정 전 대상 시나리오와 같은 요청을 수십 건
보낸 뒤(별도 RUN_ID로 구분) 본 측정을 시작하고, 워밍업 데이터도 같은 정리 범위에 포함한다.

**k6 실행기는 `caffeinate -i`로 감싼다(macOS).** 무인 실행 중 절전이 걸리면 Go monotonic clock이 잠든
시간을 빼고 재서 클라이언트 duration이 조용히 왜곡된다(실측: 서버 2,433ms 요청이 454ms로 기록).
의심스러우면 서버 접근 로그(`http.access`의 `latencyMs`)를 권위로 쓴다.

사다리를 올리기 전에 VU 1로 end-to-end median을 확인한다.

**저VU 단계(≤5)의 기록값은 단발이 아니라 5회 반복의 풀링 중앙값으로 잡는다.** 표본 1~5개짜리
단계는 큐 위치·GC 추첨의 분산이 단계 간 차이보다 커서 단발로는 역전이 흔하다(실측 3회 재발).
고VU 단계(≥10)는 표본이 커서 단발로 충분하다.

```bash
RUN_ID=20260806-01 BASE_URL=https://dev.laimory.app VUS=1 CONFIRM_AI_NOOP=yes \
  k6 run load-tests/timeline-draft/k6/calendar-core.js
```

제공된 dev median 기준값은 `/status` 34ms, 1좌표 POST 163ms, 18좌표 POST 287ms다. geo 값이 이 범위를
크게 벗어나면 #251에서 임의 지연을 만들지 않고 #257의 latency profile을 보정한다.

### 5. calendar-core 사다리

```bash
RUN_ID=20260806-01 BASE_URL=https://dev.laimory.app CONFIRM_AI_NOOP=yes \
  load-tests/timeline-draft/scripts/run-ladder.sh calendar-core
```

`1 → 10 → 50 → 100 → 300 → 500 → 1000` 순서로 올라가며, 한 단계라도 gate에 걸리면 거기서 멈춘다.
단계마다 `recordDate`가 하루씩 밀린다 — `daily_records`가 `(user_id, record_date)` UNIQUE라 같은 날짜를
재사용하면 두 번째 단계가 INSERT 대신 UPDATE가 되어 작업 성격이 달라지기 때문이다.

단계 사이에는 PROCESSING TTL 3분이 지나도록 기본 190초 쉰다(`COOLDOWN_SECONDS`로 조정).

### 6. geo 사다리

**전환(사용자 직접 수행)** — dev host에서 `.env`의 다음 두 값을 고치고 반영한다.

```text
APP_GEO_KAKAO_BASE_URL=http://<SIMULATOR_PRIVATE_IP>:8080
KAKAO_REST_API_KEY=k6-257-dummy
```

```bash
sudo vi /home/ubuntu/app/.env
sudo ./dev-recreate.sh
```

`APP_GEO_MODE=kakao`와 `APP_AI_MODE=noop`은 이미 맞춰진 상태여야 한다.

전환 직후 simulator journal을 reset하고 1좌표 요청 하나를 보내 **coord2address 1회 + keyword 1회,
unmatched 0**을 확인한다. 이것이 "실제 Kakao로 나가지 않았다"의 유일한 실증이다 — k6는 애플리케이션이
어느 base URL을 보는지 알 수 없다.

```bash
RUN_ID=20260806-01 BASE_URL=https://dev.laimory.app CONFIRM_AI_NOOP=yes CONFIRM_SIMULATOR=yes \
  load-tests/timeline-draft/scripts/run-ladder.sh geo-day
```

geo-day 사다리는 `1 → 2 → 3 → 5 → 10 → 20`으로 짧다 — 2 VU에서 pool 용량(40)에 닿고 3 VU부터 거절이
예상되어, 전이 구간 밖은 같은 실패의 반복이기 때문이다. 보고할 값은 최대 VU가 아니라
**좌표 누락 없이 통과한 마지막 VU**다.

**원복(사용자 직접 수행)** — 0단계에서 남긴 원본으로 되돌린다.

```bash
sudo cp -p /home/ubuntu/app/.env.before-loadtest /home/ubuntu/app/.env
sudo ./dev-recreate.sh
sudo ./dev-recreate.sh --show   # AI/geo 값이 원래대로인지 눈으로 확인
```

이후 integration smoke를 확인한 다음 simulator를 중지한다. WAS 원복 확인 전에 simulator를 먼저
내리지 않는다.

### 7. 지표 대조

k6 결과(`.artifacts/*-summary.json`)와 같은 시간대의 서버 지표를 나란히 본다.

- HTTP: 요청률·상태코드 분포, servlet 처리 스레드
- JVM: CPU, heap, GC pause
- host: memory, CPU credit(T3)
- Hikari: active/idle/pending, 획득 대기
- MySQL: 연결 수, 느린 쿼리, InnoDB 대기
- Redis: 명령률, 지연

geo run은 추가로:

- `laimory.geo.http.logical`(endpoint별 p50/p95/p99), `laimory.geo.http.attempts`,
  `laimory.geo.http.retries`, `laimory.geo.circuit.transitions`
- `reactor.netty.connection.provider.*`(pool 이름 `kakao-local`) — active/pending
- simulator 측: WireMock endpoint count, unmatched, container CPU/memory/restart/OOM

### 8. 검증과 정리

```bash
# run 결과 검증 — 단계별로 확인한다.
# @run_id는 RUN_ID, @scenario_step은 시나리오 코드(c|m|gd) + 단계 번호다(예: geo-day 2단계 → gd1).
sed -e "s/REPLACE_WITH_RUN_ID/20260806-01/" -e "s/REPLACE_WITH_SCENARIO_STEP/g13/" \
  load-tests/timeline-draft/sql/04-verify-run.sql \
  | mysql --defaults-extra-file=<config> <db>

# 삭제 예정 행 수 → manifest에 기록
mysql --defaults-extra-file=<config> <db> < load-tests/timeline-draft/sql/05-cleanup-dry-run.sql

# 실제 삭제
mysql --defaults-extra-file=<config> <db> < load-tests/timeline-draft/sql/06-cleanup.sql

# 잔여 0 확인(모든 residue_rows가 0)
mysql --defaults-extra-file=<config> <db> < load-tests/timeline-draft/sql/07-verify-residue.sql

# Redis 잔여 확인
REDIS_HOST=<host> REDIS_PREFIX=dev_ \
  load-tests/timeline-draft/scripts/verify-redis-residue.sh
```

dry-run의 `timeline_items (orphan-to-be)`는 0이어야 한다. 0이 아니면 AI가 결과를 저장했다는 뜻이라
noop 격리 전제가 깨진 것이므로 삭제를 진행하지 말고 원인을 먼저 확인한다.

정리 범위에 대해 알아둘 것:

- 삭제 기준은 **합성 사용자 집합 하나뿐**이다(`provider='KAKAO' AND provider_user_id LIKE 'k6-251-%'`).
  날짜·rawId·user_id 범위는 기준이 아니므로, 실제 사용자가 같은 날짜에 기록을 갖고 있거나 `k6-`로 시작하는
  rawId를 손으로 넣어 뒀어도 지워지지 않는다. 합성 event와 실제 event에 함께 연결된 item도 남는다
  (모든 연결이 합성일 때만 삭제 대상이다).
- run 단위 선택 삭제는 없다. 한 번 실행하면 `k6-251-` 사용자 전체와 그들의 데이터가 함께 사라진다.
  여러 회차를 비교 중이라면 마지막에 한 번만 돌린다.
- `refresh_tokens`·`push_registrations`는 dry-run이 세기만 하고 cleanup이 지우지 않는다. 합성 사용자는
  로그인·푸시 등록을 하지 않으므로 0이어야 하며, 0이 아니면 전제가 깨진 것이라 사람이 직접 확인한다.

Redis는 수동 삭제가 필요 없다. task 키와 사용자 index 키는 TTL 3분으로 사라지고, 전역 PROCESSING index는
stuck 지표가 scrape될 때 TTL 밖 member를 prune한다.

마지막으로 토큰과 artifact를 지우고 격리를 검증한다.

```bash
rm -f load-tests/timeline-draft/.artifacts/tokens.json \
      load-tests/timeline-draft/.artifacts/user-ids.txt
load-tests/timeline-draft/scripts/verify-artifact-hygiene.sh
```

## 환경변수

| 이름 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `RUN_ID` | ✅ | — | run 식별자. 결과 파일명과 rawId에 들어간다 |
| `BASE_URL` | ✅ | — | 대상(예: `https://dev.laimory.app`) |
| `VUS` | ✅ | — | 이 단계의 VU 수(= 사용자 수). `run-ladder.sh`가 주입한다 |
| `CONFIRM_AI_NOOP` | 원격 대상 ✅ | — | `yes`가 아니면 localhost 외 대상에서 실행을 거부한다 |
| `CONFIRM_SIMULATOR` | geo만 ✅ | — | `yes`가 아니면 geo 스크립트가 실행을 거부한다 |
| `STEP_INDEX` | | `0` | 사다리 단계 번호. recordDate를 하루씩 민다 |
| `RECORD_DATE_BASE` | | `2031-01-01` | 합성 날짜 대역의 시작 |
| `TOKENS_FILE` | | `.artifacts/tokens.json` | token 파일 경로 |
| `ARTIFACT_DIR` | | `load-tests/timeline-draft/.artifacts` | 결과 출력 경로 |
| `START_DELAY_MS` | | `5000` | barrier까지의 대기(모든 VU가 도달할 시간) |
| `REQUEST_BUDGET_MS` | | `60000` | barrier 이후 허용 시간 |
| `MAX_ERROR_RATE` | | `0.01` | `draft_accepted` gate |
| `MAX_P95_MS` | | `3000` | `draft_req_duration` p95 gate |
| `MAX_START_WINDOW_MS` | | `1000` | request start window gate |
| `LADDER` | | 시나리오별 | 사다리 재정의(공백 구분) |
| `COOLDOWN_SECONDS` | | `190` | 단계 사이 대기 |

## 완료 기준

- core·geo k6 스크립트, SQL fixture, token generator와 runbook이 저장소에 있고 secret·runtime artifact가 없다.
- 1,000개의 서로 다른 user/token 요청이 1초 start window 안에서 각각 정확히 한 번 시작됐음을 k6
  `request_start_offset_ms`와 `04-verify-run.sql`의 `distinct_users`로 함께 증명한다.
- `calendar-core`(최소)·`mixed-day`(실측 행 수)·`geo-day`(실측 + 좌표) 결과가 분리돼 기록된다.
- 실제 Kakao·실제 AI로 대량 호출이 나가지 않았다(simulator journal count, `APP_AI_MODE=noop`).
- 인스턴스·commit·DB 규모·simulator·runner 조건과 단계별 처리량, p95/p99, 오류율, resource 지표가 기록된다.
- geo run 뒤 dev 설정 원복과 integration smoke를 확인한 다음 simulator를 중지했다.
- MySQL/Redis 잔여가 0이고 `.artifacts/`의 token이 삭제됐다.
