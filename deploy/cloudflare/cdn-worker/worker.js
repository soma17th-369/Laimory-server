// cdn.laimory.app — 사진·문의 첨부·공지 썸네일 서빙 Worker (#642).
//
// R2 binding(BUCKET)에서 먼저 읽고, 없으면 이전 저장소의 공개 CDN(FALLBACK_ORIGIN)에서 가져와 응답하면서
// R2에도 저장한다(S3 → R2 이전 기간 전용 폴백 — 이전이 끝나면 FALLBACK_ORIGIN을 비워 끈다).
// 배포·바인딩은 README.md 참고. key(=subject 해시가 든 경로)는 로그에 남기지 않는다.

// 서버가 만드는 key 형태만 받는다: {sha256 hex}/photos|inquiries/{file}, notices/{file}.
const KEY_PATTERN = /^(?:[0-9a-f]{64}\/(?:photos|inquiries)\/[^/]+|notices\/[^/]+)$/;

// 엣지(공유 캐시)에만 1일 — 브라우저에는 캐시 지시를 주지 않던 이전 CloudFront 동작과 같다.
const FOUND_CACHE_CONTROL = 's-maxage=86400';

export default {
  async fetch(request, env, ctx) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response(null, { status: 405, headers: { allow: 'GET, HEAD' } });
    }
    const key = new URL(request.url).pathname.slice(1);
    if (!KEY_PATTERN.test(key)) {
      return notFound();
    }

    if (request.method === 'HEAD') {
      const head = await env.BUCKET.head(key);
      if (head !== null) {
        return new Response(null, { status: 200, headers: objectHeaders(head) });
      }
      return fallback(request, env, ctx, key);
    }

    const object = await env.BUCKET.get(key, { range: request.headers, onlyIf: request.headers });
    if (object === null) {
      return fallback(request, env, ctx, key);
    }
    const headers = objectHeaders(object);
    if (!('body' in object)) {
      // 조건부 요청의 precondition이 성립하지 않아 R2가 본문을 주지 않았다.
      const notModified = request.headers.has('if-none-match') || request.headers.has('if-modified-since');
      return new Response(null, { status: notModified ? 304 : 412, headers });
    }
    if (request.headers.has('range') && object.range) {
      headers.set('content-range', contentRange(object.range, object.size));
      return new Response(object.body, { status: 206, headers });
    }
    return new Response(object.body, { status: 200, headers });
  },
};

function objectHeaders(object) {
  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set('etag', object.httpEtag);
  headers.set('accept-ranges', 'bytes');
  headers.set('cache-control', FOUND_CACHE_CONTROL);
  return headers;
}

function contentRange(range, size) {
  let offset;
  let length;
  if ('suffix' in range) {
    length = Math.min(range.suffix, size);
    offset = size - length;
  } else {
    offset = range.offset ?? 0;
    length = range.length ?? size - offset;
  }
  return `bytes ${offset}-${offset + length - 1}/${size}`;
}

// 없는 key의 404는 캐시하지 않는다 — 곧 올라올 객체를 가리지 않게.
function notFound() {
  return new Response('Not Found', { status: 404, headers: { 'cache-control': 'no-store' } });
}

async function fallback(request, env, ctx, key) {
  if (!env.FALLBACK_ORIGIN) {
    return notFound();
  }
  const upstream = await fetch(`https://${env.FALLBACK_ORIGIN}/${key}`);
  console.log(JSON.stringify({ event: 'fallback', status: upstream.status }));
  if (upstream.status !== 200 || upstream.body === null) {
    return notFound();
  }

  const contentType = upstream.headers.get('content-type') ?? 'application/octet-stream';
  const contentLength = upstream.headers.get('content-length');
  const headers = new Headers({ 'content-type': contentType, 'cache-control': FOUND_CACHE_CONTROL });
  if (contentLength !== null) {
    headers.set('content-length', contentLength);
  }

  let body = upstream.body;
  // R2 put은 길이를 아는 stream만 받는다 — 길이를 모르면 저장 없이 응답만 하고 다음 요청에서 다시 폴백한다.
  if (contentLength !== null) {
    const [toClient, toBucket] = upstream.body.tee();
    body = toClient;
    const fixed = new FixedLengthStream(Number(contentLength));
    ctx.waitUntil(Promise.all([
      toBucket.pipeTo(fixed.writable),
      env.BUCKET.put(key, fixed.readable, { httpMetadata: { contentType } }),
    ]).catch((error) => console.log(JSON.stringify({ event: 'fallback_store_failed', error: error?.name ?? 'Error' }))));
  }
  return new Response(request.method === 'HEAD' ? null : body, { status: 200, headers });
}
