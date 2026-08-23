// `.artifacts/tokens.json`(generate-tokens.py 출력)을 VU 순서 배열로 읽는다.
//
// SharedArray는 파일을 VU마다 복사하지 않고 한 번만 파싱해 공유한다 — 1,000 VU에서 메모리·init 시간을
// 아끼기 위해 필수다. 기본 경로는 이 모듈 기준 상대 경로라 repo 어느 위치에서 k6를 실행해도 같은 파일을 연다.

import { SharedArray } from 'k6/data';

const DEFAULT_TOKENS_PATH = '../../.artifacts/tokens.json';

export const users = new SharedArray('users', function () {
  const path = __ENV.TOKENS_FILE || DEFAULT_TOKENS_PATH;
  let document;
  try {
    document = JSON.parse(open(path));
  } catch (error) {
    throw new Error(
      `token 파일을 읽지 못했습니다(${path}). generate-tokens.py를 먼저 실행하세요: ${error.message}`
    );
  }
  if (!document || !Array.isArray(document.users) || document.users.length === 0) {
    throw new Error(`token 파일에 users 배열이 없습니다: ${path}`);
  }
  // exp가 이미 지난 파일로 1,000 VU를 쏘면 전부 401이 된다 — 실행 전에 막는다.
  const expiresAt = document.expiresAtEpochSeconds;
  if (typeof expiresAt === 'number' && expiresAt * 1000 <= Date.now()) {
    throw new Error('token이 이미 만료됐습니다. generate-tokens.py로 다시 발급하세요.');
  }
  return document.users;
});
