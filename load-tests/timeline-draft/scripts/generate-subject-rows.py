#!/usr/bin/env python3
"""#251 합성 사용자의 subject 행 생성기(표준 라이브러리만 사용).

`01-seed-users.sql`은 `users` 행만 만든다. 그런데 서버는 인증 사용자의 콘텐츠 owner를
`user_subject_links`에서 찾고, 없으면 자동 생성하지 않고 fail-closed한다
(`SubjectMappingService.getRequired`). 그래서 mapping 없이 합성 사용자로 draft를 만들면
요청마다 500이다. 가입 transaction(`NewUserProvisioner`)이 한 번에 만드는 세 행을 여기서 같이 만든다.

  1. `user_subject_links`      — lookup key = HMAC-SHA-256(현재 key, "content-subject-lookup:v1" ‖ userId 8-byte BE)
  2. `subject_preferences`     — 가입 기본값(마스터 ON, 온보딩 미완료)
  3. `daily_notification_preferences` — **가입 기본값과 달리 OFF로 만든다**(아래)

일일 리마인더만 기본값을 뒤집는다. 가입 기본은 ON이지만, 합성 사용자 1,000명을 ON으로 심으면
매일 21:00 발송 worker가 그만큼을 claim 대상으로 스캔한다 — 부하 테스트가 상시 배치에 영향을
남기지 않도록 OFF로 심는다. 행 자체는 만든다(부재는 서버가 깨진 불변식으로 취급한다).

**subject의 권위는 DB다.** 이미 mapping이 있는 사용자(과거 seed + 이후 backfill로 생긴 행)의 subject는
이 스크립트가 정하지 않는다 — lookup key로 `user_subject_links`를 조회해 실제 값을 쓴다. 파생 UUID는
mapping이 아예 없는 신규 행에만 제안값으로 들어간다(`INSERT IGNORE`라 기존 행은 그대로 남는다).
파생값을 그대로 정리 대상 집합으로 믿으면, backfill이 만든 subject를 가진 사용자에서 집합이 통째로
어긋난다(설정 INSERT는 FK 위반으로 조용히 건너뛰고 정리는 아무것도 지우지 않는다).

파생은 결정적이다(같은 사용자 + 같은 secret → 같은 값). 신규 행 제안값이 재실행에도 흔들리지 않게 하는
용도이며, 그 이상의 권위는 없다.

secret은 인자가 아니라 환경변수 `SUBJECT_HMAC_SECRET`으로만 받는다(프로세스 목록·shell history
노출 방지). 값은 애플리케이션이 Secrets Manager에서 읽는 JSON 문자열 그대로다:
`{"currentVersion":n,"currentKey":"<base64 32바이트>"}`. 출력 파일에는 secret도, key 바이트도
들어가지 않는다.

사용 예:

    SUBJECT_HMAC_SECRET="$(cat ~/laimory-dev-subject-secret.json)" \
      python3 load-tests/timeline-draft/scripts/generate-subject-rows.py \
        --user-ids load-tests/timeline-draft/.artifacts/user-ids.txt

출력(둘 다 `.artifacts/`, 각각 자기 lookup key 임시 테이블을 들고 있어 단독 실행된다):

  subject-seed.sql — 위 세 테이블 INSERT(멱등). mapping이 이미 있으면 links는 건너뛰고 설정 행만 채운다.
  subject-set.sql  — 정리·검증 SQL이 읽는 TEMPORARY TABLE `k6_251_subjects`를 **DB에서** 만든다.
                     같은 세션에서 05/06/07보다 먼저 실행해야 한다(임시 테이블은 세션 범위).
                     06이 mapping을 지운 뒤에는 다시 만들 수 없으므로 07까지 같은 세션에서 이어 쓴다.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import struct
import sys
import uuid
from pathlib import Path

# 애플리케이션 `SubjectLookupKeyDeriver.CONTEXT`와 같은 값이어야 한다 — 다르면 서버가 만든 lookup key와
# 다른 PK를 심게 되어 mapping을 찾지 못한다(500). 이 문자열이 곧 계약이다.
LOOKUP_CONTEXT = b"content-subject-lookup:v1"
# subject UUID 파생 전용 context. lookup key와 같은 key를 쓰되 message context를 분리한다.
# 이 값은 이 스크립트만 쓰는 합성 규칙이라 서버 계약이 아니다.
SUBJECT_CONTEXT = b"k6-251-subject:v1"

KEY_LENGTH_BYTES = 32
MAX_USERS = 5000
CHUNK_ROWS = 500
MODIFIED_BY = "k6-251"

SUBJECT_SET_TABLE = "k6_251_subjects"


def load_secret() -> tuple[bytes, int]:
    """`SUBJECT_HMAC_SECRET`(애플리케이션과 같은 JSON 스키마)에서 현재 key와 version을 읽는다.

    rotation 기간의 previous key는 쓰지 않는다 — 새 mapping insert는 서버도 항상 current key로 한다.
    실패 메시지에 secret 원문이나 key 바이트를 담지 않는다(애플리케이션 parser와 같은 규칙).
    """
    raw = os.environ.get("SUBJECT_HMAC_SECRET", "")
    if not raw:
        raise SystemExit("환경변수 SUBJECT_HMAC_SECRET이 필요합니다(인자로 받지 않습니다).")
    try:
        document = json.loads(raw)
    except ValueError:
        raise SystemExit("SUBJECT_HMAC_SECRET이 올바른 JSON이 아닙니다.")
    if not isinstance(document, dict):
        raise SystemExit("SUBJECT_HMAC_SECRET은 JSON 객체여야 합니다.")

    version = document.get("currentVersion")
    if not isinstance(version, int) or isinstance(version, bool) or not 0 < version <= 32767:
        raise SystemExit("SUBJECT_HMAC_SECRET currentVersion이 양의 SMALLINT가 아닙니다.")

    encoded = document.get("currentKey")
    if not isinstance(encoded, str):
        raise SystemExit("SUBJECT_HMAC_SECRET currentKey가 문자열이 아닙니다.")
    try:
        key = base64.b64decode(encoded, validate=True)
    except ValueError:
        raise SystemExit("SUBJECT_HMAC_SECRET currentKey가 올바른 base64가 아닙니다.")
    if len(key) != KEY_LENGTH_BYTES:
        raise SystemExit(f"SUBJECT_HMAC_SECRET currentKey는 {KEY_LENGTH_BYTES}바이트여야 합니다.")
    return key, version


def derive(key: bytes, context: bytes, user_id: int) -> bytes:
    """애플리케이션과 같은 message 구성: context ‖ userId 8-byte big-endian."""
    return hmac.new(key, context + struct.pack(">q", user_id), hashlib.sha256).digest()


def lookup_key_hex(key: bytes, user_id: int) -> str:
    return derive(key, LOOKUP_CONTEXT, user_id).hex()


def subject_id(key: bytes, user_id: int) -> str:
    """결정적 subject UUID. 서버가 `requireUuidV4`로 version 4·variant RFC 4122를 검사하므로
    파생 바이트에 그 비트를 강제한다(값 자체는 난수가 아니라 secret 의존 파생값이다)."""
    raw = bytearray(derive(key, SUBJECT_CONTEXT, user_id)[:16])
    raw[6] = (raw[6] & 0x0F) | 0x40  # version 4
    raw[8] = (raw[8] & 0x3F) | 0x80  # variant RFC 4122
    return str(uuid.UUID(bytes=bytes(raw)))


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
            # 서버의 파생기도 양수 userId만 받는다 — 같은 경계에서 미리 막는다.
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


def chunks(rows: list[str]) -> list[list[str]]:
    return [rows[i:i + CHUNK_ROWS] for i in range(0, len(rows), CHUNK_ROWS)]


def render_insert(table: str, columns: str, rows: list[str]) -> str:
    """VALUES 목록을 CHUNK_ROWS 단위로 끊어 INSERT IGNORE 문장들을 만든다.

    IGNORE는 재실행 안전성 때문이다 — 이미 심은 사용자를 건너뛴다(01-seed-users.sql과 같은 성격).
    """
    statements = []
    for chunk in chunks(rows):
        values = ",\n    ".join(chunk)
        statements.append(f"INSERT IGNORE INTO {table} ({columns}) VALUES\n    {values};")
    return "\n\n".join(statements)


LOOKUP_KEY_TABLE = "k6_251_lookup_keys"


def render_lookup_keys_block(pairs: list[tuple[int, str, str]]) -> str:
    """lookup key 임시 테이블. 두 출력 파일이 각자 들고 있어 단독 실행된다.

    이 테이블이 있어야 "합성 사용자의 subject"를 DB에서 정확히 집을 수 있다 — 콘텐츠 소유자는
    subject_id뿐이고 `users`와 평문 join이 불가능하므로(가명화 설계), 사용자 쪽 좌표는 lookup key가 유일하다.
    """
    statements = [f"""DROP TEMPORARY TABLE IF EXISTS {LOOKUP_KEY_TABLE};

CREATE TEMPORARY TABLE {LOOKUP_KEY_TABLE} (
    user_lookup_key BINARY(32) NOT NULL,
    PRIMARY KEY (user_lookup_key)
) ENGINE=InnoDB;"""]
    rows = [f"(UNHEX('{hex_key}'))" for _, hex_key, _ in pairs]
    for chunk in chunks(rows):
        values = ",\n    ".join(chunk)
        statements.append(f"INSERT INTO {LOOKUP_KEY_TABLE} (user_lookup_key) VALUES\n    {values};")
    return "\n\n".join(statements)


def build_seed_sql(pairs: list[tuple[int, str, str]], version: int) -> str:
    links = [f"(UNHEX('{hex_key}'), '{subject}', {version})" for _, hex_key, subject in pairs]
    return f"""-- #251 합성 사용자의 subject 행. generate-subject-rows.py가 만든 파일이다(직접 수정하지 않는다).
--
-- 01-seed-users.sql → 02-export-user-ids.sql 다음에 적용한다. 재실행해도 안전하다(INSERT IGNORE).
--
-- mapping이 이미 있는 사용자는 links INSERT가 건너뛰어지고, 설정 행은 **DB에 있는 실제 subject**로
-- 채워진다(아래 INSERT ... SELECT). 파생 UUID를 설정 행에 그대로 쓰면 backfill로 생긴 subject를 가진
-- 사용자에서 FK 위반이 나고, INSERT IGNORE가 그것을 경고로 삼켜 조용히 아무것도 안 심는다.
--
-- 감사 컬럼은 JPA auditing을 거치지 않으므로 직접 채운다. dev-mysql 호스트는 UTC이고 애플리케이션은
-- Asia/Seoul 벽시계로 DATETIME을 저장하므로 NOW()를 그대로 쓰면 앱 기준 9시간 과거가 된다.
--
-- daily_notification_preferences는 가입 기본값(ON)과 달리 **OFF**로 심는다 — 합성 사용자가 매일 21:00
-- 발송 worker의 스캔 대상이 되지 않게 한다. next_due_at은 NOT NULL이라 값을 채우되 enabled=FALSE라
-- worker가 claim하지 않는다. ⚠️ 이미 ON으로 존재하는 행은 IGNORE가 건너뛴다(끄지 않는다).
--
-- 사용(한 세션에서 통째로):
--   mysql --defaults-extra-file=... <db> < load-tests/timeline-draft/.artifacts/subject-seed.sql

{render_lookup_keys_block(pairs)}

SET @now = CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00');
SET @next_due_at = TIMESTAMP(DATE(@now) + INTERVAL 1 DAY, '21:00:00');

-- 1) mapping. 파생 subject는 **행이 없을 때만** 쓰이는 제안값이다.
{render_insert('user_subject_links', 'user_lookup_key, subject_id, lookup_key_version', links)}

-- 2~3) subject 축 설정. 실제 subject를 mapping에서 읽어 채운다.
INSERT IGNORE INTO subject_preferences
    (subject_id, push_enabled, onboarding_completed, created_at, updated_at, modified_by)
SELECT l.subject_id, TRUE, FALSE, @now, @now, '{MODIFIED_BY}'
FROM user_subject_links l
JOIN {LOOKUP_KEY_TABLE} k ON k.user_lookup_key = l.user_lookup_key;

INSERT IGNORE INTO daily_notification_preferences
    (subject_id, enabled, next_due_at, created_at, updated_at, modified_by)
SELECT l.subject_id, FALSE, @next_due_at, @now, @now, '{MODIFIED_BY}'
FROM user_subject_links l
JOIN {LOOKUP_KEY_TABLE} k ON k.user_lookup_key = l.user_lookup_key;

-- 확인: 세 값이 모두 합성 사용자 수와 같아야 한다.
-- (한 문장이 임시 테이블을 두 번 참조하면 ERROR 1137이라 문장을 나눈다.)
SELECT 'user_subject_links' AS target_table, COUNT(*) AS rows_present
FROM user_subject_links l
JOIN {LOOKUP_KEY_TABLE} k ON k.user_lookup_key = l.user_lookup_key;

SELECT 'subject_preferences' AS target_table, COUNT(*) AS rows_present
FROM subject_preferences p
JOIN user_subject_links l ON l.subject_id = p.subject_id
JOIN {LOOKUP_KEY_TABLE} k ON k.user_lookup_key = l.user_lookup_key;

SELECT 'daily_notification_preferences' AS target_table, COUNT(*) AS rows_present
FROM daily_notification_preferences d
JOIN user_subject_links l ON l.subject_id = d.subject_id
JOIN {LOOKUP_KEY_TABLE} k ON k.user_lookup_key = l.user_lookup_key;
"""


def build_set_sql(pairs: list[tuple[int, str, str]]) -> str:
    return f"""-- #251 합성 subject 집합. generate-subject-rows.py가 만든 파일이다(직접 수정하지 않는다).
--
-- 05/06/07 SQL의 삭제·검증 경계다. subject_id는 `users`와 평문으로 join할 수 없으므로(그게 설계다)
-- lookup key로 mapping을 조회해 **DB에 있는 실제 subject**를 담는다 — 파생값을 그대로 쓰지 않는다.
--
-- ⚠️ 임시 테이블은 세션 범위다 — 이 파일과 대상 SQL을 **한 세션에서** 실행해야 한다:
--   cat load-tests/timeline-draft/.artifacts/subject-set.sql \\
--       load-tests/timeline-draft/sql/05-cleanup-dry-run.sql | mysql --defaults-extra-file=... <db>
--
--   cat load-tests/timeline-draft/.artifacts/subject-set.sql \\
--       load-tests/timeline-draft/sql/06-cleanup.sql \\
--       load-tests/timeline-draft/sql/07-verify-residue.sql | mysql --defaults-extra-file=... <db>
--
-- ⚠️ 06이 mapping을 지운 뒤에는 이 파일로 집합을 다시 만들 수 없다(조회할 행이 사라진다).
--    06 → 07을 다른 세션으로 나누고 이 파일을 다시 흘려 넣으면 **빈 집합**이 만들어져, 07의
--    "(synthetic subject)" 검사가 무엇이 남았든 0을 돌려준다. 반드시 한 세션에서 이어서 실행한다.

{render_lookup_keys_block(pairs)}

DROP TEMPORARY TABLE IF EXISTS {SUBJECT_SET_TABLE};

CREATE TEMPORARY TABLE {SUBJECT_SET_TABLE} (
    subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (subject_id)
) ENGINE=InnoDB;

INSERT INTO {SUBJECT_SET_TABLE} (subject_id)
SELECT l.subject_id
FROM user_subject_links l
JOIN {LOOKUP_KEY_TABLE} k ON k.user_lookup_key = l.user_lookup_key;

-- 확인: 정리 **전**에는 합성 사용자 수와 같아야 한다. 적으면 mapping이 없는 사용자가 있다는
-- 뜻이다(seed 미적용). 06을 이미 실행한 뒤라면 0이 정상이지만, 그 상태로 07을 이어 붙이면
-- 검증이 무의미해진다 — 위 ⚠️를 참고해 06과 같은 세션에서 실행한다.
SELECT COUNT(*) AS synthetic_subjects FROM {SUBJECT_SET_TABLE};
"""


def main() -> int:
    parser = argparse.ArgumentParser(description="#251 합성 사용자의 subject 행 생성")
    parser.add_argument("--user-ids", required=True, type=Path,
                        help="user_id 목록 파일(한 줄에 하나, 02-export-user-ids.sql 출력)")
    parser.add_argument("--out-dir", type=Path, default=None,
                        help="출력 디렉터리(기본 .artifacts)")
    args = parser.parse_args()

    key, version = load_secret()
    user_ids = read_user_ids(args.user_ids)
    out_dir = args.out_dir or (Path(__file__).resolve().parent.parent / ".artifacts")
    out_dir.mkdir(parents=True, exist_ok=True)

    pairs = [(user_id, lookup_key_hex(key, user_id), subject_id(key, user_id)) for user_id in user_ids]
    subjects = {subject for _, _, subject in pairs}
    if len(subjects) != len(pairs):
        # 32바이트 파생값이 충돌할 확률은 무시할 수 있지만, 조용히 사용자 하나를 잃는 대신 멈춘다.
        raise SystemExit("파생된 subject UUID가 중복입니다 — 생성을 중단합니다.")

    seed_path = out_dir / "subject-seed.sql"
    set_path = out_dir / "subject-set.sql"
    seed_path.write_text(build_seed_sql(pairs, version), encoding="utf-8")
    set_path.write_text(build_set_sql(pairs), encoding="utf-8")

    # secret·key 바이트는 출력하지 않는다 — 개수·version·경로만.
    print(f"users               : {len(pairs)}")
    print(f"lookup key version  : {version}")
    print(f"written to          : {seed_path}")
    print(f"                      {set_path}")
    print("subject-seed.sql을 적용한 뒤 토큰을 발급한다. subject-set.sql은 정리·검증 세션에서 먼저 실행한다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
