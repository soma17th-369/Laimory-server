# cdn Worker (`cdn.laimory.app`의 origin)

사진·문의 첨부·공지 썸네일을 R2에서 서빙하는 Cloudflare Worker다(#642). 서버는 `PHOTO_CDN_DOMAIN`(`cdn.laimory.app`)을
URL에 붙이고, 앱·관리자 웹·AI는 그 URL을 바로 GET한다.

## 요청 경로

```text
클라이언트 → cdn.laimory.app = Amazon CloudFront (엣지 캐시)
               └ 캐시 미스 → cdn-origin.laimory.app = 이 Worker → R2 (이전 기간에는 R2에 없으면 폴백)
```

`cdn.laimory.app`은 CloudFront 배포를 가리키는 DNS-only CNAME이고, Worker는 Workers custom domain
`cdn-origin.laimory.app`에만 붙어 있다. Cloudflare Free 프록시는 국내 일부 통신사(예: KT) 요청을 미국 서부 엣지로
보내 사진 응답이 크게 느려졌기 때문에, 클라이언트가 처음 붙는 지점을 서울 엣지가 있는 CloudFront로 두었다.
CloudFront에서 Worker로 가는 캐시 미스는 Cloudflare 서울 데이터센터에서 처리되는 것을 확인했다.

## 동작

- `GET`·`HEAD`만 받는다. key가 서버 규칙(`{sha256 hex}/photos|inquiries/{file}`, `notices/{file}`)이 아니면 404.
- R2 binding `BUCKET`에서 읽는다. Range·조건부 요청(`If-None-Match` 등)은 R2가 처리한다.
- 찾은 객체는 `Cache-Control: s-maxage=86400` — 엣지에만 1일, 브라우저 캐시 지시는 없다(이전 CloudFront와 같음).
- 404는 `no-store`로 캐시하지 않는다.
- **폴백(S3 → R2 이전 기간 전용)**: R2에 없고 `FALLBACK_ORIGIN`이 설정돼 있으면 그 공개 CDN에서 가져와 응답하고
  R2에도 저장한다. 이벤트 로그 `{"event":"fallback","status":N}` 1줄로 폴백 횟수를 센다. 이전이 끝나면
  `FALLBACK_ORIGIN` 바인딩을 빼서 끈다(코드 변경 없음).
- key(=subject 해시가 든 경로)는 로그에 남기지 않는다 — invocation log는 끄고 위 이벤트만 남긴다.

## 배포 (수동, Cloudflare MCP 또는 API)

`wrangler` 프로젝트로 두지 않는다. 스크립트 업로드 metadata에 아래를 넣는다. 실제 bucket 이름·origin 도메인은
저장소에 적지 않는다.

```json
{
  "main_module": "worker.js",
  "compatibility_date": "<배포일>",
  "bindings": [
    { "type": "r2_bucket", "name": "BUCKET", "bucket_name": "<R2 bucket>" },
    { "type": "plain_text", "name": "FALLBACK_ORIGIN", "text": "<이전 CDN 도메인>" }
  ],
  "cache_options": { "enabled": true },
  "observability": { "enabled": true, "logs": { "enabled": true, "invocation_logs": false } }
}
```

도메인은 Workers custom domain으로 `cdn-origin.laimory.app`에 붙인다(DNS 레코드·인증서 자동). `cdn.laimory.app`에는
붙이지 않는다 — 그 이름은 CloudFront가 받고, 같은 이름을 origin으로 쓰면 CloudFront가 자기 자신을 부른다.

## CloudFront 앞단 (수동 구성)

계정·배포 ID는 저장소에 적지 않는다. 정액 요금제(Free)는 관리형 정책만 허용하므로 아래처럼 둔다.

- origin: `cdn-origin.laimory.app`, HTTPS only(TLS 1.2 이상).
- 캐시 정책: 관리형 `CachingOptimized` — Worker 응답의 `s-maxage=86400`을 따라 엣지 1일 캐시.
- origin 요청 정책: **없음**. Host가 `cdn-origin.laimory.app`으로 가야 한다. 클라이언트 Host(`cdn.laimory.app`)를
  넘기는 정책을 쓰면 Cloudflare가 그 이름을 Worker로 보내지 못한다.
- 허용 메서드 GET·HEAD, HTTP는 HTTPS로 리다이렉트, 대체 도메인 `cdn.laimory.app` + us-east-1 ACM 인증서.
- 접근 로그는 끈다 — 경로에 subject 해시가 들어 있다.
- 정액 요금제는 WAF web ACL 연결이 필수다. 플랜 구독 흐름이 만든 web ACL을 쓴다(따로 만든 web ACL을 종량제
  배포에 붙이면 별도 과금).

## 검증

```bash
node --test deploy/cloudflare/cdn-worker/worker.test.mjs
```

R2·폴백 origin을 메모리 fake로 바꾼 단위 테스트다. 배포 후에는 두 단을 나눠 확인한다.

- 앞단: `https://cdn.laimory.app/<key>` 응답 헤더 `x-cache`(Hit/Miss)·`x-amz-cf-pop`(엣지 위치).
  `cdn.laimory.app/cdn-cgi/trace`는 CloudFront 캐시를 거치므로 엣지 확인에 쓰지 않는다.
- Worker: `https://cdn-origin.laimory.app/<key>` 응답과 `cf-cache-status`(Workers Cache). CloudFront가 보낸 요청이
  어느 Cloudflare 데이터센터에서 처리됐는지는 Cloudflare 분석에서 `cdn-origin.laimory.app` 호스트의 colo로 본다.
