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
- `terraform/storage_cdn.tf`, IAM and deploy workflow

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
- transient failure만 콜당 최대 2회 시도하고(좌표당 최대 4회 요청), 최종 실패는 draft 생성을 실패시킨다.
- transport는 WebClient(reactive)이고 타임아웃은 `spring.http.reactiveclient.*`가 SSOT다
  (`spring.http.client.*`는 블로킹 클라이언트용으로 별개).
- 좌표 간 병렬 fan-out: unique coordinate들을 동시 최대 `APP_GEO_LOOKUP_CONCURRENCY`(기본 20)개까지
  병렬 조회한다. 카카오 일 쿼터는 엔드포인트당 100,000건이지만 초당 한도는 존재하되 수치 비공개라
  무제한 대신 상한을 둔다(429 관측 시 값을 낮추는 것이 즉시 완화책). 병렬화로 실패 코드(1014/1015)는
  배치 종합이 아니라 "가장 먼저 관측된 실패"의 분류다 — 전이·영구가 경쟁하면 비결정(둘 다 502, 수용된
  트레이드오프).
- `app.geo.kakao-base-url`은 운영 endpoint 변경용이 아니라 MockWebServer용 test seam이다.
- 좌표, request URL/query, response body를 log하지 않는다.
- `address`, `places`, `durationText`는 server-derived이며 client 값을 무시한다.

credential 이름은 `KAKAO_REST_API_KEY`다. 값은 복제하지 않는다.

### S3 and CloudFront

- client는 server-issued presigned PUT URL로 S3에 직접 업로드한다.
- signature는 content type과 content length를 묶는다.
- object key는 `{sha256hex(userId)}/photos/{filename}`으로 server가 파생한다.
- response/AI가 쓰는 `photoUrl`은 unsigned CloudFront URL이며 payload에 materialize한다.
- key/CDN domain 변경은 기존 payload backfill을 검토한다.
- cleanup은 만료 draft photo object를 먼저 지우고 성공 뒤 DB row를 삭제한다.

관련 이름은 `AWS_REGION`, `PHOTO_S3_BUCKET`, `PHOTO_CDN_DOMAIN`과 upload limit property들이다.
실제 bucket, domain, credential 값은 knowledge에 복제하지 않는다.

### Firebase Cloud Messaging (타임라인 완료 푸시)

- mode는 `noop|firebase`(`app.push.mode`, 기본 noop) — noop에서도 FID 등록 API/DB는 동작하고
  외부 발송만 없다. 알 수 없는 mode는 sender 빈 부재로 기동 실패한다.
- Firebase Admin Java `9.10.0` 고정. 발송 target은 **FID(Firebase Installation ID)**다 — 9.10.0에서
  Send API `fid` target이 추가되고 registration token target이 deprecated돼, 새 코드에서
  `setToken/addToken/addAllTokens`를 쓰지 않는다.
- Android 선행조건: `firebase-messaging 25.1.1+`, manifest
  `firebase_messaging_installation_id_enabled=true`, `onRegistered(fid)`가 준 FID를 등록 API로 업로드.
- 발송은 AI callback이 처음 확정한 terminal(SUCCESS/FAILED 모두) 뒤 비동기 best-effort 1회다.
  메시지는 일반 문구 notification + data(`taskId`, `status`) 조합이고 Android TTL 1시간, 기본
  priority다. 타임라인 결과·오류 원문·기록 내용은 싣지 않는다(polling이 권위이자 유실 안전망).
- multicast는 호출당 최대 500 FID chunk(입력 순서 보존)로 나누고 response index로 실패 FID를
  매핑한다. `UNREGISTERED`와 target-level `INVALID_ARGUMENT`만 등록 삭제 대상이다(server-built
  payload 정상은 unit test로 고정). 인증·project mismatch·quota·internal 오류는 삭제 근거가 아니며,
  SDK 내부 재시도 후에도 실패한 전이 오류는 로그만 남긴다(durable retry/outbox 없음).
- credential은 ADC로만 읽는다 — `GOOGLE_APPLICATION_CREDENTIALS`에 컨테이너 내부 read-only
  service-account JSON **파일 경로**만 두고, JSON 원문을 property/Git/이미지/Terraform에 넣지 않는다.
  firebase 모드에서 ADC/초기화 실패는 기동 실패다(fail-fast).
- log에는 taskId·status·개수·오류 분류만 남긴다 — FID·Firebase 응답 원문·credential 금지.

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
