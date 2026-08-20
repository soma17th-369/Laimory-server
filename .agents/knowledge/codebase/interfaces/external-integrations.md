# External Integrations

## Scope

OAuth provider, Kakao Maps, S3/CloudFront, FCM push와 외부 AI mode의 현재 adapter 계약을 설명한다.

## Read When

외부 endpoint, credential 이름, validation, retry, timeout, object key 또는 provider mode를 바꿀 때 읽는다.

## Authoritative Sources

- `application*.properties`
- OAuth/security config and provider user services
- `geo/**` providers and tests
- `timeline/photo/**`, S3/CDN config and tests
- `push/**` sender/config and tests
- live IAM and deploy workflow

## Current Implementation

### Google and Kakao OIDC

- Google/Kakao login을 지원하고 provider `sub`로 user를 식별한다.
- Kakao는 issuer claim을 의도적으로 요구하지 않고 JWK signature, audience와 nonce를 검증한다.
- Kakao scope는 `openid,profile_nickname`이며 콘솔 동의항목(닉네임) 활성화가 선행 조건이다
  (미설정 상태로 요청하면 KOE 에러). 닉네임은 id_token claim으로 받고 UserInfo endpoint는 호출하지 않는다.
- OAuth handshake만 Redis-backed session을 사용한다.
- 자체 access/refresh token과 provider token을 같은 용어로 부르지 않는다.

관련 변수 이름은 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`,
`KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`이다. 값은 문서에 기록하지 않는다.

### Kakao Maps

- mode는 `noop|kakao`다. dev workflow는 Kakao mode를 켜고 기본값은 noop이다.
- unique coordinate마다 reverse geocoding 1회 + 주소(도로명 우선, 지번 fallback)를 질의어로 한
  keyword place search 1회, 정상 2회 외부 호출한다(주소가 없는 좌표는 keyword search를 생략해 1회).
- keyword search 결과는 "같은 주소(건물)의 입주 장소" 보장이 아니라 주소 질의어 + 반경 50m 매칭의
  실측 관찰 기반 휴리스틱이다.
- **HTTP 실행과 품질 판정 분리**: 외부 호출은 unique coordinate로 dedupe하고, 예상된 실패는 error가
  아니라 좌표별 최종 outcome으로 materialize해 나머지 조회를 계속한다(`GeoLookupOutcome`).
  부분 실패 허용/거절은 timeline 계층의 품질 판정(`GeoEnrichmentPolicy`)이 aggregate로 정한다 —
  unique 실패 20% 초과(`5F > U`) 또는 시간순 연속 실패 3개면 502로 거절, 그 외에는 성공 좌표를
  보존하고 실패 좌표만 `address` 생략·`places=[]`로 계속한다.
- 오류 코드: 거절된 batch의 materialized 실패 중 영구(`clientMayRetryLater=false` — 429·401·403·기타
  non-2xx·decode/shape)가 하나라도 있으면 `-1015`, 아니면 `-1014`다(first-observed 경쟁 제거).
- retry는 멱등 Kakao GET 한정, 전이 실패(`retryThisCall=true` — 5xx·I/O·connect/response timeout)만
  콜당 최대 2회(`RetryHelper` — exponential backoff+jitter, 좌표당 최대 4회 요청). local pool 거절·
  circuit open·logical deadline은 즉시 재시도하지 않는다(두 축 분류는 `MapPlaceLookupException`).
- transport는 전용 WebClient(reactive)다 — `app.geo.http.*`가 timeout SSOT이고 Kakao 전용
  `ConnectionProvider`(pool `kakao-local`: active/pending 유계, acquire timeout, idle/lifetime eviction),
  connect/response timeout, retry·backoff 포함 logical call deadline, Reactor Netty 숨은 retry
  비활성화(`disableRetry`)를 `KakaoGeoHttpConfiguration`이 배선한다(kakao mode 한정 생성, context
  종료 시 dispose).
- process-wide circuit breaker(`kakao-local`, Resilience4j count-based)가 두 endpoint의 remote attempt를
  함께 계수한다. 성공은 유효 2xx(`documents=[]` 포함), 실패는 429 포함 non-2xx·I/O·timeout·decode/shape다.
  local pool 거절·logical deadline·open 거절은 통계에서 ignore한다(local saturation이 remote 건강도를
  오염시키지 않음). open이면 wire 구독 전에 차단되고 automatic half-open transition은 꺼져 있다
  (open wait 경과 뒤 다음 호출이 probe).
- 좌표 간 병렬 fan-out: unique coordinate들을 요청당 동시 최대 `APP_GEO_LOOKUP_CONCURRENCY`(기본 20)개까지
  병렬 조회한다. 카카오 일 쿼터는 엔드포인트당 100,000건이지만 초당 한도는 존재하되 수치 비공개라
  무제한 대신 상한을 둔다. process 전체 상한은 전용 pool(기본 active 20·pending 20)이 담당한다.
- 공개 입력 상한: rawId dedupe·기존 저장 item 제외 뒤 unique coordinate 최대 30개
  (`app.geo.max-unique-coordinates`) — 초과는 외부 호출 전 400/`-400`. 제품 계약이라 운영 tuning으로
  낮추지 않는다.
- `app.geo.kakao-base-url`은 운영 endpoint 변경용이 아니라 MockWebServer용 test seam이다.
- 좌표, request URL/query, response body를 log/metric tag에 넣지 않는다(관측 계약은
  [observability](../operations/observability.md)).
- `address`, `places`, `durationText`는 server-derived이며 client 값을 무시한다.

credential 이름은 `KAKAO_REST_API_KEY`다. 값은 복제하지 않는다.

### S3 and CloudFront

- client는 server-issued presigned PUT URL로 S3에 직접 업로드한다.
- signature는 content type과 content length를 묶는다.
- object key namespace는 subject 기반
  `{hex(SHA-256(subjectId 16 bytes))}/photos/{filename}` 단일 규칙이다.
  presign/enrich/Event PATCH/cleanup/delete job 모두 `PhotoObjectKeys.subjectFullKey`과
  `PhotoUrlService.buildSubjectUrl`을 사용한다.
- response/AI가 쓰는 `photoUrl`은 unsigned CloudFront URL이며 payload에 materialize한다.
- Event PATCH의 수동 PHOTO는 client가 S3 업로드 성공 뒤 보내는 계약이다. 서버는 object 존재 여부를
  HEAD하지 않고, 입력에 `description`·`photoUrl`을 받지 않으며 `description=null`과 server-derived
  `photoUrl`을 final payload에 저장한다.
- key/CDN domain 변경은 기존 payload backfill을 검토한다.
- cleanup은 만료 draft photo object를 먼저 지우고 성공 뒤 DB row를 삭제한다.
- finalized PHOTO는 Event/DailyRecord hard delete transaction이 원문 PHOTO Item과 MySQL delete-job을
  남긴 뒤 별도 worker가 verbose `DeleteObjects`로 처리한다. 성공 job과 Item만 한 DB transaction에서
  삭제하고 Error·응답 누락·SDK 예외면 둘 다 남겨 재시도한다.

관련 이름은 `AWS_REGION`, `PHOTO_S3_BUCKET`, `PHOTO_CDN_DOMAIN`과 upload limit property들이다.
실제 bucket, domain, credential 값은 knowledge에 복제하지 않는다.

### Firebase Cloud Messaging (타임라인 완료 푸시·일일 리마인더)

- mode는 `noop|firebase`(`app.push.mode`, 기본 noop) — noop에서도 FID 등록 API/DB는 동작하고
  외부 발송만 없다. 알 수 없는 mode는 sender 빈 부재로 기동 실패한다.
- Firebase Admin Java `9.10.0` 고정. 발송 target은 **FID(Firebase Installation ID)**다 — 9.10.0에서
  Send API `fid` target이 추가되고 registration token target이 deprecated돼, 새 코드에서
  `setToken/addToken/addAllTokens`를 쓰지 않는다.
- Android 선행조건: `firebase-messaging 25.1.1+`, manifest
  `firebase_messaging_installation_id_enabled=true`, `onRegistered(fid)`가 준 FID를 등록 API로 업로드.
- sender는 typed `PushMessage`(종류 + data)와 FID 목록을 받는다(#314). 고정 title/body는
  `PushMessageType`이 소유하고 호출자가 문구를 만들지 않는다. 결과에 따라 문구가 달라지면 종류를 나누고
  (`TIMELINE_COMPLETION_SUCCESS`/`_FAILED`), 나뉜 종류는 같은 `metricGroup()`을 공유해 발송 metric의
  `type` 차원이 늘지 않는다. 현재 모든 종류가 정보성 통지다 — 일일 리마인더는 기본 ON 일괄 발송이고
  수신거부 수단은 종류별 OFF다(#318). 광고성 알림을
  추가하려면 수신 동의·야간 제한·`(광고)` 표기·무료 수신거부 수단을 함께 도입해야 한다.
- 타임라인 완료 발송은 AI callback이 처음 확정한 terminal(SUCCESS/FAILED 모두) 뒤 비동기 best-effort
  1회다. 메시지는 일반 문구 notification + data(`taskId`, `status`) 조합이고 Android TTL 1시간, 기본
  priority다. 타임라인 결과·오류 원문·기록 내용은 싣지 않는다(polling이 권위이자 유실 안전망).
  **예정 알림 마스터를 읽지 않는다** — 사용자가 직접 시작한 작업의 결과 통지라 리텐션 알림 스위치의
  적용 대상이 아니다(#319). 사용자가 이 통지를 끄는 수단은 OS 알림 권한이다.
- FID 등록·해제·callback 발송 대상 조회의 owner는 UUID subjectId다. 인증된 앱 요청은 MVC 경계에서 매핑된
  subject를 쓰고, callback은 Redis task의 subject owner로 FID를 조회한다(raw userId 역조회 없음).
- multicast는 호출당 최대 500 FID chunk(입력 순서 보존)로 나누고 response index로 실패 FID를
  매핑한다. `UNREGISTERED`와 target-level `INVALID_ARGUMENT`만 등록 삭제 대상이다(server-built
  payload 정상은 unit test로 고정). 인증·project mismatch·quota·internal 오류는 삭제 근거가 아니며,
  SDK 내부 재시도 후에도 실패한 전이 오류는 로그만 남긴다(durable retry/outbox 없음).
- 무효 등록 삭제는 발송 대상 조회 snapshot 시각 기준 **조건부**다(`last_registered_at <= snapshot`) —
  지연 도착한 무효 응답이 snapshot 이후 같은 FID로 갱신된 정상 재등록을 지우지 않는다.
- credential은 ADC로만 읽는다 — `GOOGLE_APPLICATION_CREDENTIALS`에 컨테이너 내부 read-only
  service-account JSON **파일 경로**만 두고, JSON 원문을 property/Git/이미지에 넣지 않는다.
  파일은 컨테이너 runtime user(appuser, UID 1001)가 읽을 수 있어야 한다(chown 1001·0400).
  firebase 모드에서 ADC/초기화 실패는 기동 실패다(fail-fast). Admin SDK HTTP timeout은
  기본 0(무한)이라 `FirebasePushConfig`가 connect/read/write 유한값을 강제한다.
- log에는 알림 종류·개수·오류 분류만 남긴다 — FID·subjectId·Firebase 응답 원문·credential 금지.

### AI

현재 외부 production AI adapter는 없다. `noop`과 dev/test `fake`만 있으며 상세 계약은
[AI contract](ai-contract.md)를 따른다.

## Invariants

- 환경변수·property 이름과 역할만 문서화한다.
- token, secret, credential, presigned URL, 실제 provider payload를 log/knowledge에 남기지 않는다.
- server-derived payload field를 client 값으로 덮어쓰지 않는다.

## Known Gaps

- production AI adapter와 delivery retry가 없다.
- 실제 OAuth round trip은 환경별 provider credential과 redirect URI 운영 설정이 필요하다.
- orphan presigned S3 object cleanup은 없다.

## Update When

provider, mode, API call/retry/validation, credential 이름, photo key/CDN materialization 또는 IAM 경계가
바뀔 때 갱신한다.

## Validation

```bash
./gradlew test --tests 'com.laimory.server.auth.*' \
  --tests 'com.laimory.server.geo.*' \
  --tests 'com.laimory.server.timeline.photo.*'
```
