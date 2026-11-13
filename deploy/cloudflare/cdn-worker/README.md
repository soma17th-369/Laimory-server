# cdn Worker (`cdn.laimory.app`)

사진·문의 첨부·공지 썸네일을 R2에서 서빙하는 Cloudflare Worker다(#642). 서버는 `PHOTO_CDN_DOMAIN`으로
이 도메인을 URL에 붙이고, 앱·관리자 웹·AI는 그 URL을 바로 GET한다.

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

도메인은 Workers custom domain으로 `cdn.laimory.app`에 붙인다(DNS 레코드·인증서 자동).

## 검증

```bash
node --test deploy/cloudflare/cdn-worker/worker.test.mjs
```

R2·폴백 origin을 메모리 fake로 바꾼 단위 테스트다. Workers Cache·실제 R2 동작은 배포 후 `cf-cache-status`와
응답으로 확인한다.
