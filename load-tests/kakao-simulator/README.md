# Kakao Local API simulator

Issue [#257](https://github.com/soma17th-369/Laimory-server/issues/257)의 부하 테스트용 service virtualization
artifact다. 현재 `KakaoMapPlaceProvider`가 사용하는 Kakao Local API 두 경로를 WireMock으로 대체하고,
[#276](https://github.com/soma17th-369/Laimory-server/issues/276)에서 가짜 AI 서버 경로 두 개를 추가했다
([가짜 AI 경로](#가짜-ai-경로) 참조).

이 simulator의 목적은 실제 Kakao API를 호출하지 않으면서 애플리케이션의 전용 WebClient, process-wide
connection pool, pending acquire, timeout, retry, circuit breaker와 servlet worker 대기까지 실제 HTTP 경로로
측정하는 것이다. Kakao 전체 API, TLS 비용, quota/QPS 제한 또는 장애 응답은 재현하지 않는다.

## 안전 경계

- 실제 Kakao REST API key, 실제 사용자 좌표·주소·검색어를 넣지 않는다.
- `Authorization`은 고정된 비밀 아닌 값 `KakaoAK k6-257-dummy`만 허용한다.
- 일치하지 않는 method, path, header 또는 query는 WireMock 기본 `404`로 실패한다.
- AWS에서는 public IP, inbound 22, 공개 8080, ALB, DNS와 TLS를 만들지 않는다.
- 기존 AWS 리소스는 조회하거나 새 리소스에서 참조만 하며 설정·정책·내용을 수정하거나 삭제하지 않는다.
- simulator EC2, SG, IAM과 artifact 저장소는 모두 #257 전용 신규 임시 리소스로 만든다.
- dev WAS 환경변수·container 전환과 부하 실행은 사용자가 직접 수행하며 이 runbook의 실행 agent 범위가 아니다.

## 제공 계약

| 순서 | 요청 | 응답에서 애플리케이션이 소비하는 값 |
|---|---|---|
| 1 | `GET /v2/local/geo/coord2address.json?x=<longitude>&y=<latitude>` | 도로명 주소, 지번 주소 fallback, 건물명 |
| 2 | `GET /v2/local/search/keyword.json?query=<address>&x=<longitude>&y=<latitude>&radius=50&sort=distance` | 순서대로 non-blank `place_name` |

두 요청에는 다음 header가 필요하다.

```text
Authorization: KakaoAK k6-257-dummy
```

coord2address가 고정 도로명 주소를 반환하므로 정상 좌표 한 개는 두 endpoint를 순차로 한 번씩 호출한다.
keyword 결과는 카페와 식당 두 개이고, provider는 coord2address의 건물명을 앞에 추가한 뒤 최종 장소 목록을
최대 10개로 제한한다.

## 가짜 AI 경로

Issue [#276](https://github.com/soma17th-369/Laimory-server/issues/276)의 fake AI 서버 stub이다.

**소유권: test 환경 전용 자산이다(#400).** dev는 `agentcore`로 전환해 이 시뮬레이터를 더 쓰지 않는다.
webhook 콜백 URL·SG 인바운드는 test WAS를 향하며, mapping은 한 벌뿐이라 두 환경을 동시에 섬기지 않는다.
서버의 `HttpTimelineAiDispatcher`·user-memory dispatcher가 보내는 dispatch를 202로 접수하고, WireMock
core webhook(3.1.0+ 내장, 별도 extension 불필요)으로 지연 뒤 서버 콜백 endpoint를 호출한다.

| stub | 요청 매칭(불일치는 404) | 접수 응답(50ms 지연) | webhook(접수 후 2000ms 지연, 단발) |
|---|---|---|---|
| `ai-timeline-dispatch` | `POST /v1/timeline`, body에 `taskId`·`taskToken`·`dailyRecordId`·`window.startAt` 필수 | `202 {"taskId":"<요청 taskId echo>","status":"PROCESSING"}` | `POST {WAS}/s/api/v1/timeline/drafts/{taskId}/callback`, header `Task-Token: <요청 taskToken>`, body `{"status":"FAILED","errorCode":-1008,"error":"kakao-simulator fake AI"}` |
| `ai-user-memory-dispatch` | `POST /v1/user-memory`, body에 `taskId`·`taskToken`·`dailyTimelines` 필수 | 동일 형식 echo | `POST {WAS}/s/api/v1/user-memory/updates/{taskId}/result`, 접수 `Task-Token` 그대로, body `{"status":"SUCCESS","userMemory":{…stub 고정 문서…},"errorCode":null,"error":null}` |

커버리지 분담:

| 흐름 | 담당 |
|---|---|
| http dispatch 접수(202 taskId echo·PROCESSING) | 시뮬레이터 |
| 타임라인 FAILED 콜백 전체 루프 | 시뮬레이터 webhook |
| user-memory 저장(result) happy path | 시뮬레이터 webhook |
| 타임라인 SUCCESS 체인 | 서버의 `app.ai.mode=fake` — input→result→callback의 2단 토큰 회전과 결과 body의 `sourceRawIds`가 실제 draft rawId여야 해서 WireMock webhook으로는 구조적으로 불가 |

### 콜백 URL 하드코딩

webhook URL의 `10.0.9.207:8080`은 **test WAS** private IP다(#400에서 dev → test 이관). JSON mapping에는 주석을 넣을 수 없어 여기에
기록한다. **WAS IP·포트·콜백 경로가 바뀌면** `mappings/ai-timeline.json`과
`mappings/ai-user-memory.json`의 `serveEventListeners[0].parameters.url`을 수정하고 container를
recreate한다. 콜백 지연을 바꿀 때도 같은 위치의 `delay.milliseconds`를 수정한다.

### dev 적용 절차

dispatch body에는 콜백 URL이 없고 AI 측 설정을 전제하므로, 서버는 base URL만 바꾸면 된다.

```text
APP_AI_MODE=http
APP_AI_HTTP_BASE_URL=http://SIMULATOR_PRIVATE_IP:8080
```

선행 조건: WAS→시뮬레이터 인바운드는 test WAS private IP가, 콜백 역방향(시뮬레이터→WAS 8080)은
WAS SG의 시뮬레이터 SG 소스 허용이 담당한다 — 둘 다 #400 Phase 1에서 적용됐다. geo만 측정하는 #251 부하
테스트에서는 기존대로 `APP_AI_MODE=noop`을 유지한다.

기대 루프: draft 생성 → 202 접수 → 약 2초 뒤 FAILED 콜백 → 폴링이 FAILED 반환.
user-memory 배치 dispatch는 약 2초 뒤 result 저장까지 자동 완료된다.

### 실 AI와의 차이

- webhook은 **단발**이다. 실 AI의 재시도(5xx·timeout에만 3회, 0.5→1→2s backoff, 401/404/409는 침묵
  중단)는 흉내내지 않는다. 콜백이 유실되면 재전송 없이 끝난다.
- 타임라인은 항상 `FAILED`(errorCode `-1008`) 고정, user-memory는 항상 `SUCCESS` 고정이다.
- `userMemory` 문서는 stub 고정값이며 실제 dailyTimelines 내용을 반영하지 않는다.
- 시뮬레이터 EC2에서 계약 검증 스크립트를 실행하면 C9·C11·C14의 정상 dispatch가 실제 dev WAS로
  랜덤 taskId의 콜백을 발사한다. 서버는 task 없음 404(`-1001`)로 거절하므로 무해하지만, 부하 측정
  중에는 검증 스크립트를 돌리지 않는다.

## 디렉터리

```text
load-tests/kakao-simulator/
├── compose.yaml
├── mappings/
│   ├── ai-timeline.json
│   ├── ai-user-memory.json
│   ├── coord2address.json
│   └── keyword.json
├── __files/
│   ├── coord2address-response.json
│   └── keyword-response.json
├── simulator-preflight.js
└── scripts/
    └── verify-contract.sh
```

## 로컬 실행

선행 조건은 Docker daemon, Docker Compose, `curl`, Python 3과 k6다.

```bash
docker compose -f load-tests/kakao-simulator/compose.yaml config --quiet
docker compose -f load-tests/kakao-simulator/compose.yaml up -d --wait
curl --fail http://127.0.0.1:8080/__admin/health
```

WireMock image는 `wiremock/wiremock:3.13.2`로 고정한다. container 안에 `curl`이나 `wget`이 있다고
가정하지 않고 host에서 health를 확인한다.

종료:

```bash
docker compose -f load-tests/kakao-simulator/compose.yaml down
```

## 계약과 simulator capacity 검증

결정적 계약 검증은 health/version, 정상 응답 shape와 50ms 지연, 잘못된 인증·query의 404, journal reset,
endpoint별 호출 수와 unmatched 0을 확인한다(C1~C8). 가짜 AI 경로는 C9~C14로 검증한다: 202 taskId
echo·PROCESSING shape, 필수 필드 누락 body의 404, webhook 발사 내용·타이밍, AI 경로 journal 카운트.

```bash
load-tests/kakao-simulator/scripts/verify-contract.sh
```

C13 webhook 검증은 스크립트가 로컬 HTTP 수신기(기본 포트 `18099`)를 띄우고, Admin API로 콜백 URL만
수신기로 바꾼 임시 mapping을 등록해 dispatch한 뒤 수신 요청의 경로·`Task-Token`·body와 약 2초 지연
(1.5~8초 허용)을 assert하고 임시 mapping을 삭제한다. 파일 mapping의 실환경 콜백 URL은 그대로 유지된다.
WireMock container가 수신기에 접근하는 host는 기본 `host.docker.internal`(macOS/Windows Docker
Desktop)이며, Linux host나 원격 검증에서는 override한다.

```bash
WEBHOOK_HOST_FROM_SIMULATOR=172.17.0.1 WEBHOOK_RECEIVER_PORT=18099 \
  load-tests/kakao-simulator/scripts/verify-contract.sh
```

원격 simulator를 검사할 때만 base URL을 바꾼다. 이때 `WEBHOOK_HOST_FROM_SIMULATOR`는 simulator에서
스크립트 실행 머신으로 접근 가능한 IP여야 한다.

```bash
SIMULATOR_BASE_URL=http://SIMULATOR_PRIVATE_IP:8080 \
  load-tests/kakao-simulator/scripts/verify-contract.sh
```

현재 WAS active connection 20보다 여유가 있는지 확인하는 직접 40-concurrent preflight:

```bash
k6 inspect load-tests/kakao-simulator/simulator-preflight.js
k6 run load-tests/kakao-simulator/simulator-preflight.js
```

원격 실행:

```bash
SIMULATOR_BASE_URL=http://SIMULATOR_PRIVATE_IP:8080 \
  k6 run load-tests/kakao-simulator/simulator-preflight.js
```

preflight는 40 VU가 각각 한 번 호출하도록 고정해 정확히 40개의 coord2address 요청, 2xx 40개, unmatched 0을
요구한다. `simulator_delay_overhead_ms` p95는 기본 100ms 미만이어야 하며 초과하면 k6가 실패한다. 환경별로
더 엄격한 근거가 있을 때만 `MAX_P95_OVERHEAD_MS`를 낮춘다. 동시에 수집한
`docker stats --no-stream`에서 restart 또는 OOM이 보이면 #251 부하 테스트를 시작하지 않는다.

## 애플리케이션 연결 smoke

Spring의 environment property 변환을 사용하므로 Java나 `application.properties` 수정 없이 다음 변수를
override한다.

```text
APP_GEO_MODE=kakao
APP_GEO_KAKAO_BASE_URL=http://127.0.0.1:8080
KAKAO_REST_API_KEY=k6-257-dummy
```

로컬에서 simulator와 애플리케이션의 기본 8080이 충돌하므로 애플리케이션만 다른 port로 실행한다.

```bash
SPRING_PROFILES_ACTIVE=docker \
APP_GEO_MODE=kakao \
APP_GEO_KAKAO_BASE_URL=http://127.0.0.1:8080 \
KAKAO_REST_API_KEY=k6-257-dummy \
SERVER_PORT=8081 \
./gradlew bootRun
```

#251의 인증된 한 좌표 timeline 요청을 `http://127.0.0.1:8081`로 보낸 뒤 journal을 확인한다. 기대 결과는
coord2address 1회, keyword 1회, unmatched 0이며 주소와 장소는 다음과 같다.

```text
address: 서울 테스트구 시뮬레이터로 251
places: Laimory 테스트빌딩, Laimory 테스트카페, Laimory 테스트식당
```

실제 Kakao key나 network는 이 검증에 필요하지 않다.

## 지연 보정

두 mapping의 `fixedDelayMilliseconds=50`은 로컬 smoke 기본값이지 실제 Kakao latency 측정값이 아니다.
현재 참고 median은 `/status` 34ms, 한 좌표 timeline POST 163ms, 18좌표 병렬 POST 287ms다. draft core
비용이 포함돼 있으므로 `129ms / 2`를 endpoint별 지연으로 그대로 나누지 않는다.

#251 authoritative run 전에 다음 순서로 보정한다.

1. 같은 환경에서 Kakao 호출이 없는 `calendar-core` median을 측정한다.
2. 가능하면 `laimory.geo.http.logical{endpoint}`의 coord2address와 keyword p50/p95/p99를 수집한다.
3. endpoint 값이 없으면 mapping 지연을 조정하면서 VU 1의 한 좌표와 18좌표 end-to-end median을 각각
   163ms와 287ms의 ±15%에 맞춘다.
4. mapping 수정 뒤 container를 recreate하고 계약 검증과 40-concurrent preflight를 다시 실행한다.
5. 확정한 두 지연, image RepoDigest, fixture checksum을 run manifest에 기록한다.

지연을 바꿨다면 검증 스크립트와 k6에도 같은 값을 전달한다.

```bash
EXPECTED_DELAY_MS=80 MINIMUM_DELAY_MS=75 \
  load-tests/kakao-simulator/scripts/verify-contract.sh
EXPECTED_DELAY_MS=80 \
  k6 run load-tests/kakao-simulator/simulator-preflight.js
```

overhead gate를 명시할 때:

```bash
EXPECTED_DELAY_MS=80 MAX_P95_OVERHEAD_MS=100 \
  k6 run load-tests/kakao-simulator/simulator-preflight.js
```

실측 분포가 확보되기 전에는 random delay profile을 추가하지 않는다.

## 재현 정보와 artifact 만들기

image pull 뒤 digest를 기록한다.

```bash
docker pull wiremock/wiremock:3.13.2
docker image inspect --format '{{index .RepoDigests 0}}' wiremock/wiremock:3.13.2
```

fixture checksum:

```bash
shasum -a 256 \
  load-tests/kakao-simulator/mappings/ai-timeline.json \
  load-tests/kakao-simulator/mappings/ai-user-memory.json \
  load-tests/kakao-simulator/mappings/coord2address.json \
  load-tests/kakao-simulator/mappings/keyword.json \
  load-tests/kakao-simulator/__files/coord2address-response.json \
  load-tests/kakao-simulator/__files/keyword-response.json
```

secret 없는 simulator directory만 package한다.

```bash
env COPYFILE_DISABLE=1 \
  tar --format=ustar -czf /tmp/kakao-simulator.tar.gz -C load-tests kakao-simulator
shasum -a 256 /tmp/kakao-simulator.tar.gz
```

`COPYFILE_DISABLE=1`과 portable `ustar` format은 macOS extended attribute가 Linux에서 `._*` 파일로
풀려 WireMock mapping으로 오인되는 일을 막는다.

run manifest에는 최소한 다음을 남긴다.

```text
run-id
WireMock RepoDigest
artifact SHA-256
mapping/response SHA-256
coord2address fixed delay
keyword fixed delay
simulator instance ID/private IP/type
시작/종료 UTC
contract 및 preflight 결과
```

## AWS EC2 runbook

### 1. 읽기 전용 preflight

`--profile sandbox --region ap-northeast-2`로 caller identity와 dev WAS의 VPC, AZ, subnet, private IP,
security group, route, IAM profile과 SSM Online 상태를 조회한다. 이어 같은 VPC·AZ의 private subnet, NAT 또는
필요한 VPC endpoint, AL2023 x86_64 최신 AMI, `m7i.large` 제공 여부와 artifact bucket을 확인한다.

live resource ID는 바뀔 수 있으므로 이 README에 고정하지 않는다. 실행 직전 조회 결과로 creation manifest를
만들고 다음 값과 종료 예정 시각을 운영자에게 보여준 뒤 승인을 받는다.

### 2. 생성 계약

- AMI: 실행 시점 최신 Amazon Linux 2023 x86_64
- instance: `m7i.large` 우선, 동일 계열 fixed-performance `large` 대안
- fallback: `t3.large`만 허용하며 CPU credit을 결과에 포함
- root: gp3 10GiB, encrypted, `DeleteOnTermination=true`
- network: dev WAS와 같은 VPC·가능하면 같은 AZ의 private subnet
- public IPv4/EIP/key pair: 없음
- tag: `Name=laimory-kakao-simulator-257`, `Purpose=load-test`, `Issue=257`, 승인된 UTC `ExpiresAt`
- 신규 전용 IAM role/profile: SSM managed node와 신규 전용 artifact bucket의 exact object `s3:GetObject`만 허용
- 신규 SG inbound: TCP 8080, source는 실행 직전 조회한 dev WAS private IPv4 `/32`만
- 금지: `0.0.0.0/0`, inbound 22, public 8080, ALB, DNS, TLS termination

기존 VPC와 private subnet에는 새 EC2를 연결만 하고 route/NACL/subnet 설정은 바꾸지 않는다. 기존 application
SG·IAM role·bucket은 재사용하거나 수정하지 않는다.

### 3. artifact 전달과 host bootstrap

기존 bucket에는 object를 쓰지 않는다. #257 run 전용 private S3 bucket을 새로 만들고 Block Public Access와
server-side encryption을 명시한 뒤 다음 key에 artifact와 checksum을 올린다.

```text
kakao-simulator.tar.gz
kakao-simulator.tar.gz.sha256
```

instance role에는 이 신규 bucket의 두 exact object 읽기만 허용한다. private subnet에서 Docker Hub pull이
불가능하면 NAT, route 또는 public IP를 임의 추가하지 않고, Mac에서 `linux/amd64` WireMock image archive를
만들어 같은 신규 bucket으로 전달해 `docker load`한다.

SSM command로 checksum을 검증한 뒤 AL2023 host에서 실행한다.

```bash
dnf install -y docker
systemctl enable --now docker
docker version
docker pull grafana/k6:2.1.0
```

Compose plugin이 있으면 artifact directory에서 실행한다.

```bash
docker compose up -d --wait
curl --fail http://127.0.0.1:8080/__admin/health
docker stats --no-stream laimory-kakao-simulator
```

Compose가 없으면 동일 옵션의 단일 container를 실행한다.

```bash
docker run -d \
  --name laimory-kakao-simulator \
  --restart unless-stopped \
  --publish 8080:8080 \
  --volume "$PWD/mappings:/home/wiremock/mappings:ro" \
  --volume "$PWD/__files:/home/wiremock/__files:ro" \
  wiremock/wiremock:3.13.2 \
  --async-response-enabled \
  --async-response-threads=64 \
  --container-threads=32 \
  --disable-request-logging \
  --max-request-journal-entries=50000
```

host health, image digest와 checksum을 확인하고 계약 검증 및 40-concurrent preflight를 실행한다. dev WAS
host에서 private IP의 8080 health에 연결되는지는 인계 뒤 사용자가 확인한다.

private subnet의 새 EC2 내부에서 C10을 실행하므로 기존 WAS나 외부 route가 필요 없다.

```bash
docker run --rm \
  --network host \
  --env SIMULATOR_BASE_URL=http://127.0.0.1:8080 \
  --env EXPECTED_DELAY_MS=50 \
  --env MAX_P95_OVERHEAD_MS=100 \
  --volume "$PWD/simulator-preflight.js:/scripts/simulator-preflight.js:ro" \
  grafana/k6:2.1.0 \
  run /scripts/simulator-preflight.js
```

`grafana/k6:2.1.0`은 검증 시점의 local k6와 같은 버전으로 고정한다. 실행 뒤 resolved RepoDigest도 run
manifest에 기록한다.

### 4. 사용자에게 인계

simulator EC2 생성·검증 뒤 실행 agent는 dev WAS에 접속하거나 SSM command를 보내지 않는다. 다음 정보만
사용자에게 전달하고 멈춘다.

- simulator instance ID와 private IP
- simulator health와 contract/preflight 결과
- image digest, artifact와 fixture checksum, 두 endpoint 지연값
- 사용자가 직접 설정할 값:
  - `APP_GEO_KAKAO_BASE_URL=http://SIMULATOR_PRIVATE_IP:8080`
  - `KAKAO_REST_API_KEY=k6-257-dummy`
  - `APP_AI_MODE=noop`
- 사용자가 직접 실행할 journal reset, 한 좌표 smoke와 #251 절차

환경변수 변경 전 backup, container recreate, `/api/v1/intro` 확인과 WAS 원복도 모두 사용자 책임 범위다.

### 5. simulator cleanup

사용자가 WAS 원복과 테스트 종료를 확인해 준 뒤 별도 승인으로 다음 **신규 #257 리소스만** 정리한다.

1. simulator EC2를 terminate하고 신규 root volume 삭제를 확인한다.
2. 신규 simulator SG를 삭제한다.
3. 신규 IAM instance profile, role과 inline/managed policy attachment를 삭제한다.
4. 신규 artifact bucket의 두 object와 bucket을 삭제한다.

기존 VPC, subnet, route table, NACL, NAT, WAS EC2/SG/IAM과 기존 S3 bucket에는 cleanup을 수행하지 않는다.
사용자의 WAS 원복 확인 전에는 simulator를 먼저 종료하지 않는다.

## 완료 기준

- 계약 검증과 40-concurrent preflight가 오류 없이 통과한다.
- 실제 Kakao key·좌표·주소가 repository, artifact와 log에 없다.
- 한 좌표가 두 endpoint를 순차로 한 번씩 호출한다.
- 확정 지연, image digest와 fixture checksum으로 run을 재현할 수 있다.
- AWS에서는 기존 리소스를 수정하지 않고 private 경로만 사용하며, 사용자 원복 확인 뒤 신규 임시 리소스만
  모두 정리된다.

## 공식 문서

- [Kakao Local API](https://developers.kakao.com/docs/ko/local/dev-guide)
- [WireMock Docker](https://wiremock.org/docs/standalone/docker/)
- [WireMock request matching](https://wiremock.org/docs/request-matching/)
- [WireMock delay](https://wiremock.org/docs/simulating-faults/)
- [WireMock standalone options](https://wiremock.org/docs/standalone/java-jar/)
- [WireMock Admin API](https://wiremock.org/docs/standalone/admin-api-reference/)
- [AWS Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)
- [AWS security group references](https://docs.aws.amazon.com/vpc/latest/userguide/security-group-rules.html)
