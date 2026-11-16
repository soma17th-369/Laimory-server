// cdn Worker 동작 검증 — `node --test deploy/cloudflare/cdn-worker/worker.test.mjs` (Node 18+, 외부 의존성 없음).
// R2 binding과 폴백 origin을 메모리 fake로 바꿔 끼운다. 실제 Cloudflare 동작(Workers Cache 등)은 배포 후 확인한다.
import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import worker from './worker.js';

const NS = 'a'.repeat(64);
const PHOTO_KEY = `${NS}/photos/0190f0aa-0000-7000-8000-000000000001.jpg`;

// Workers 런타임 전용 FixedLengthStream — 테스트에서는 길이 검사 없는 통과 stream으로 대신한다.
globalThis.FixedLengthStream = class extends TransformStream { constructor() { super(); } };

function fakeBucket(objects = {}) {
  const store = new Map(Object.entries(objects));
  const toObject = (key, body) => ({
    size: body.length,
    httpEtag: `"etag-${key.length}"`,
    writeHttpMetadata(headers) { headers.set('content-type', 'image/jpeg'); },
    body: new Blob([body]).stream(),
  });
  return {
    store,
    async head(key) {
      return store.has(key) ? { ...toObject(key, store.get(key)), body: undefined } : null;
    },
    async get(key) {
      return store.has(key) ? toObject(key, store.get(key)) : null;
    },
    async put(key, stream, options) {
      const bytes = new Uint8Array(await new Response(stream).arrayBuffer());
      store.set(key, bytes);
      store.set(`${key}#type`, options.httpMetadata.contentType);
    },
  };
}

function fakeCtx() {
  const pending = [];
  return { pending, waitUntil(promise) { pending.push(promise); } };
}

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });

test('serves object from R2 with edge-only cache header', async () => {
  const env = { BUCKET: fakeBucket({ [PHOTO_KEY]: new Uint8Array([1, 2, 3]) }) };

  const response = await worker.fetch(new Request(`https://cdn.example/${PHOTO_KEY}`), env, fakeCtx());

  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 's-maxage=86400');
  assert.equal(response.headers.get('content-type'), 'image/jpeg');
  assert.deepEqual(new Uint8Array(await response.arrayBuffer()), new Uint8Array([1, 2, 3]));
});

test('rejects key outside server namespaces with uncached 404 and no fallback', async () => {
  let fetched = false;
  globalThis.fetch = async () => { fetched = true; return new Response('x'); };
  const env = { BUCKET: fakeBucket(), FALLBACK_ORIGIN: 'origin.example' };

  const response = await worker.fetch(new Request('https://cdn.example/etc/passwd'), env, fakeCtx());

  assert.equal(response.status, 404);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(fetched, false);
});

test('falls back to origin on R2 miss, responds, and stores object with content type', async () => {
  let requestedUrl;
  globalThis.fetch = async (url) => {
    requestedUrl = url;
    return new Response(new Uint8Array([9, 8]), { headers: { 'content-type': 'image/png', 'content-length': '2' } });
  };
  const bucket = fakeBucket();
  const ctx = fakeCtx();
  const env = { BUCKET: bucket, FALLBACK_ORIGIN: 'origin.example' };

  const response = await worker.fetch(new Request(`https://cdn.example/${PHOTO_KEY}`), env, ctx);
  const body = new Uint8Array(await response.arrayBuffer());
  await Promise.all(ctx.pending);

  assert.equal(requestedUrl, `https://origin.example/${PHOTO_KEY}`);
  assert.equal(response.status, 200);
  assert.deepEqual(body, new Uint8Array([9, 8]));
  assert.deepEqual(bucket.store.get(PHOTO_KEY), new Uint8Array([9, 8]));
  assert.equal(bucket.store.get(`${PHOTO_KEY}#type`), 'image/png');
});

test('returns uncached 404 without storing when origin also misses', async () => {
  globalThis.fetch = async () => new Response('missing', { status: 403 });
  const bucket = fakeBucket();
  const ctx = fakeCtx();
  const env = { BUCKET: bucket, FALLBACK_ORIGIN: 'origin.example' };

  const response = await worker.fetch(new Request(`https://cdn.example/${PHOTO_KEY}`), env, ctx);

  assert.equal(response.status, 404);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(ctx.pending.length, 0);
  assert.equal(bucket.store.has(PHOTO_KEY), false);
});

test('returns 404 on R2 miss when fallback origin is not configured', async () => {
  const env = { BUCKET: fakeBucket() };

  const response = await worker.fetch(new Request(`https://cdn.example/${PHOTO_KEY}`), env, fakeCtx());

  assert.equal(response.status, 404);
});

test('rejects non-read methods', async () => {
  const env = { BUCKET: fakeBucket() };

  const response = await worker.fetch(
    new Request(`https://cdn.example/${PHOTO_KEY}`, { method: 'PUT', body: 'x' }), env, fakeCtx());

  assert.equal(response.status, 405);
});
