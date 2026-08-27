#!/usr/bin/env bash
# binlog 반출 스크립트의 회귀 테스트. 코드 리뷰에서 잡힌 두 결함이 되살아나지 않는지 본다.
#   1. 덤프 좌표 검증이 SIGPIPE로 정상 덤프를 실패시키는 것
#   2. 업로드 표식이 source 세대에 묶이지 않아 세대 교체 후 정규 업로드를 건너뛰는 것
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MONITORING_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
TEST_DIR=$(mktemp -d)
trap 'rm -rf "$TEST_DIR"' EXIT

DUMP_SCRIPT="$MONITORING_DIR/scripts/backup-mysql-dump.sh"
UPLOAD_SCRIPT="$MONITORING_DIR/scripts/upload-binlog.sh"
STREAM_SCRIPT="$MONITORING_DIR/scripts/stream-binlog.sh"

for script in "$DUMP_SCRIPT" "$UPLOAD_SCRIPT" "$STREAM_SCRIPT"; do
  bash -n "$script" || { echo "syntax error: $script" >&2; exit 1; }
done

# ---------------------------------------------------------------------------
# 1. 덤프 좌표 검증은 입력을 끝까지 소비해야 한다.
#    head/grep -q로 앞부분만 읽으면 downstream이 먼저 닫혀 zcat이 SIGPIPE(141)로 끝나고,
#    pipefail이 그것을 pipeline 실패로 만들어 정상 덤프가 실패한다.
# ---------------------------------------------------------------------------
if grep -qE 'head -[0-9]+ \| grep' "$DUMP_SCRIPT"; then
  echo "backup-mysql-dump.sh must not validate the dump with 'head | grep' under pipefail" >&2
  exit 1
fi

# 실제로 shipping되는 awk 프로그램을 스크립트에서 떼어내 검증한다(사본을 테스트하지 않는다).
awk "/\\| awk '/{flag=1; next} flag && /^' \\|\\|/{exit} flag" "$DUMP_SCRIPT" > "$TEST_DIR/coordinate.awk"
[[ -s $TEST_DIR/coordinate.awk ]] ||
  { echo "could not extract the coordinate check from backup-mysql-dump.sh" >&2; exit 1; }

make_dump() { # $1=출력경로  $2=좌표 포함 여부
  python3 - "$1" "$2" <<'PY'
import gzip, sys
path, with_coordinate = sys.argv[1], sys.argv[2] == "yes"
with gzip.open(path, "wt") as handle:
    if with_coordinate:
        handle.write("-- laimory_source_uuid=1cba5a8e-7542-11f1-9074-02620b0f8f1d\n")
        handle.write("-- CHANGE MASTER TO MASTER_LOG_FILE='binlog.000068', MASTER_LOG_POS=48965;\n")
    # 50줄을 훨씬 넘겨야 조기 종료로 인한 SIGPIPE가 재현된다.
    for row in range(5000):
        handle.write(f"INSERT INTO t VALUES ({row});\n")
    handle.write("-- Dump completed on 2026-08-27 12:00:00\n")
PY
}

make_dump "$TEST_DIR/good.sql.gz" yes
make_dump "$TEST_DIR/no-coordinate.sql.gz" no

set +e
( set -o pipefail; gzip -dc "$TEST_DIR/good.sql.gz" | awk -f "$TEST_DIR/coordinate.awk" )
good_status=$?
( set -o pipefail; gzip -dc "$TEST_DIR/no-coordinate.sql.gz" | awk -f "$TEST_DIR/coordinate.awk" )
missing_status=$?
set -e

if (( good_status != 0 )); then
  echo "coordinate check rejected a valid dump (exit $good_status; 141 means SIGPIPE)" >&2
  exit 1
fi
if (( missing_status == 0 )); then
  echo "coordinate check accepted a dump without the binlog coordinate" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 2. 업로드 표식은 source 세대(server_uuid)에 묶여야 한다.
#    파일명만 쓰면 source 교체 후 새 세대가 같은 binlog 이름을 다시 쓸 때 이전 세대의 표식 때문에
#    정규 업로드가 조용히 건너뛰어진다. 재초기화로 스풀이 비면 보존 스윕이 표식에 닿지 못해
#    그 표식은 영구히 남는다.
# ---------------------------------------------------------------------------
if grep -qE '"\$UPLOADED_DIR/\$name"' "$UPLOAD_SCRIPT"; then
  echo "upload-binlog.sh must scope upload markers by server_uuid, not by file name alone" >&2
  exit 1
fi
grep -q 'marker_dir="\$UPLOADED_DIR/\$source_uuid"' "$UPLOAD_SCRIPT" ||
  { echo "upload-binlog.sh must derive the marker directory from the source uuid" >&2; exit 1; }

# 세대 교체 시나리오: 세대 A가 올린 뒤 재초기화된 세대 B가 같은 이름을 다시 써도 건너뛰지 않아야 한다.
UPLOADED_DIR="$TEST_DIR/uploaded"
SPOOL_DIR="$TEST_DIR/spool"
mkdir -p "$UPLOADED_DIR" "$SPOOL_DIR"

upload_generation() { # $1=uuid  $2..=파일명 — upload-binlog.sh의 선택·표식 규칙을 그대로 따른다
  local source_uuid=$1; shift
  local marker_dir="$UPLOADED_DIR/$source_uuid" newest="" name
  mkdir -p "$marker_dir"
  for name in "$@"; do : > "$SPOOL_DIR/$name"; done
  for name in "$@"; do
    if [[ -z $newest ]] || (( 10#${name##*.} > 10#${newest##*.} )); then newest=$name; fi
  done
  for name in "$@"; do
    [[ $name != "$newest" ]] || continue
    [[ ! -f "$marker_dir/$name" ]] || continue
    echo "$name"
    : > "$marker_dir/$name"
  done
}

generation_a=$(upload_generation 1cba5a8e-7542-11f1-9074-02620b0f8f1d \
  binlog.000001 binlog.000002 binlog.000003)
# 재초기화에서 스트림은 재개 지점 이상의 스풀 파일을 전부 지운다(새 세대는 000001부터라 전부 해당).
rm -f "$SPOOL_DIR"/binlog.*
generation_b=$(upload_generation 9f3c7d20-8811-11f1-a044-0242ac110002 \
  binlog.000001 binlog.000002 binlog.000003)

expected=$'binlog.000001\nbinlog.000002'
[[ $generation_a == "$expected" ]] ||
  { echo "generation A uploaded unexpected files: $generation_a" >&2; exit 1; }
[[ $generation_b == "$expected" ]] ||
  { echo "generation B skipped uploads after a source change: '$generation_b'" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 3. 계약 가드: 되살아나면 조용히 데이터를 잃는 결정들.
# ---------------------------------------------------------------------------
grep -q 'RestartPreventExitStatus=78' "$MONITORING_DIR/systemd/laimory-binlog-stream.service" ||
  { echo "chain-broken exit must be excluded from automatic restart" >&2; exit 1; }
grep -q 'connection-server-id' "$STREAM_SCRIPT" ||
  { echo "the stream must report a server id distinct from the source" >&2; exit 1; }
grep -q 'result-file="\$SPOOL_DIR/"' "$STREAM_SCRIPT" ||
  { echo "--result-file acts as a prefix with --raw and needs a trailing slash" >&2; exit 1; }
grep -q 'systemctl is-active' "$UPLOAD_SCRIPT" ||
  { echo "stream_up must be written by the upload timer, not by the stream itself" >&2; exit 1; }
grep -q 'force-if-open' "$MONITORING_DIR/README.md" ||
  { echo "the recovery procedure must pass --force-if-open for the tail object" >&2; exit 1; }

echo "binlog script tests passed"
