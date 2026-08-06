#!/usr/bin/env python3
"""#251 부하 테스트용 access token 생성기(표준 라이브러리만 사용).

애플리케이션의 `JwtTokens`(HS256, claim `iss`/`sub`/`iat`/`exp`)와 같은 형식의 access token을 사용자별로
발급한다. 서버는 access token을 저장하지 않고 서명·issuer·만료만 검증하므로(stateless) 로그인 흐름을 태우지
않고 오프라인으로 발급할 수 있다.

secret은 인자가 아니라 환경변수 `JWT_SECRET`으로만 받는다(프로세스 목록·shell history 노출 방지).
출력 파일에는 token 원문이 들어가므로 `.artifacts/` 아래에 0600으로만 쓴다.

사용 예:

    JWT_SECRET="$(cat ~/laimory-dev-jwt-secret)" \
      python3 load-tests/timeline-draft/scripts/generate-tokens.py \
        --user-ids load-tests/timeline-draft/.artifacts/user-ids.txt \
        --run-id 20260806-core-01
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
from pathlib import Path

ISSUER = "laimory"
ALGORITHM = "HS256"
# JwtTokens가 기동 시점에 거절하는 값과 같은 하한 — 여기서도 같은 이유로 fail-fast한다.
MIN_SECRET_BYTES = 32
DEFAULT_TTL_SECONDS = 7200
MAX_USERS = 5000


def b64url(raw: bytes) -> str:
    """JWS가 요구하는 padding 없는 base64url."""
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def sign(secret: bytes, user_id: int, issued_at: int, ttl_seconds: int) -> str:
    header = {"alg": ALGORITHM}
    claims = {
        "iss": ISSUER,
        "sub": str(user_id),
        "iat": issued_at,
        "exp": issued_at + ttl_seconds,
    }
    # separators로 공백을 제거해 결정적 직렬화를 만든다(같은 입력 → 같은 token).
    signing_input = "{}.{}".format(
        b64url(json.dumps(header, separators=(",", ":")).encode("utf-8")),
        b64url(json.dumps(claims, separators=(",", ":")).encode("utf-8")),
    )
    signature = hmac.new(secret, signing_input.encode("ascii"), hashlib.sha256).digest()
    return "{}.{}".format(signing_input, b64url(signature))


def read_user_ids(path: Path) -> list[int]:
    """`02-export-user-ids.sql` 출력(한 줄에 user_id 하나)을 읽는다."""
    user_ids: list[int] = []
    seen: set[int] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        text = line.strip()
        if not text or text.startswith("#"):
            continue
        try:
            user_id = int(text)
        except ValueError:
            raise SystemExit(f"user-ids 파일 {line_number}번째 줄이 정수가 아닙니다: {text!r}")
        if user_id <= 0:
            # 서버는 0·음수 subject를 유효한 서명이 있어도 거절한다 — 여기서 미리 막는다.
            raise SystemExit(f"user_id는 양수여야 합니다({line_number}번째 줄): {user_id}")
        if user_id in seen:
            raise SystemExit(f"user_id가 중복입니다({line_number}번째 줄): {user_id}")
        seen.add(user_id)
        user_ids.append(user_id)
    if not user_ids:
        raise SystemExit(f"user-ids 파일에 사용할 수 있는 user_id가 없습니다: {path}")
    if len(user_ids) > MAX_USERS:
        raise SystemExit(f"user_id 수가 상한 {MAX_USERS}을 넘습니다: {len(user_ids)}")
    return user_ids


def main() -> int:
    parser = argparse.ArgumentParser(description="#251 부하 테스트용 access token 발급")
    parser.add_argument("--user-ids", required=True, type=Path,
                        help="user_id 목록 파일(한 줄에 하나, 02-export-user-ids.sql 출력)")
    parser.add_argument("--out", type=Path, default=None,
                        help="출력 JSON 경로(기본 .artifacts/tokens.json)")
    parser.add_argument("--run-id", required=True,
                        help="run 식별자 — manifest와 k6 결과 파일명에 함께 쓴다")
    parser.add_argument("--ttl-seconds", type=int, default=DEFAULT_TTL_SECONDS,
                        help=f"access token 수명(기본 {DEFAULT_TTL_SECONDS}초)")
    args = parser.parse_args()

    secret_text = os.environ.get("JWT_SECRET", "")
    if not secret_text:
        raise SystemExit("환경변수 JWT_SECRET이 필요합니다(인자로 받지 않습니다).")
    secret = secret_text.encode("utf-8")
    if len(secret) < MIN_SECRET_BYTES:
        raise SystemExit(f"JWT_SECRET은 {MIN_SECRET_BYTES}바이트 이상이어야 합니다(HS256).")
    if args.ttl_seconds <= 0:
        raise SystemExit("--ttl-seconds는 양수여야 합니다.")

    user_ids = read_user_ids(args.user_ids)
    out_path = args.out or (Path(__file__).resolve().parent.parent / ".artifacts" / "tokens.json")
    out_path.parent.mkdir(parents=True, exist_ok=True)

    issued_at = int(time.time())
    document = {
        "runId": args.run_id,
        "issuer": ISSUER,
        "algorithm": ALGORITHM,
        "issuedAtEpochSeconds": issued_at,
        "expiresAtEpochSeconds": issued_at + args.ttl_seconds,
        "ttlSeconds": args.ttl_seconds,
        "userCount": len(user_ids),
        # token 순서가 VU 순서다 — k6는 `__VU - 1` 인덱스로 이 배열을 읽는다.
        "users": [
            {"userId": user_id, "token": sign(secret, user_id, issued_at, args.ttl_seconds)}
            for user_id in user_ids
        ],
    }

    # token 원문이 들어가므로 생성 시점부터 소유자 전용 권한으로 연다(먼저 만들고 chmod하면 그 사이가 열린다).
    fd = os.open(out_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(document, handle, ensure_ascii=False)
        handle.write("\n")

    expires_at = time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(issued_at + args.ttl_seconds))
    # token·secret은 출력하지 않는다 — 개수·만료·경로만.
    print(f"run-id     : {args.run_id}")
    print(f"users      : {len(user_ids)}")
    print(f"expires at : {expires_at}")
    print(f"written to : {out_path}")
    print("token 원문은 출력하지 않는다. 이 파일은 run 종료 후 삭제한다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
