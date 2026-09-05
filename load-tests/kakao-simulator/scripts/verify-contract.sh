#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
mappings_dir="${script_dir}/../mappings"

simulator_base_url="${SIMULATOR_BASE_URL:-http://127.0.0.1:8080}"
simulator_base_url="${simulator_base_url%/}"
authorization='KakaoAK k6-257-dummy'
expected_delay_ms="${EXPECTED_DELAY_MS:-50}"
webhook_receiver_port="${WEBHOOK_RECEIVER_PORT:-18099}"
webhook_host_from_simulator="${WEBHOOK_HOST_FROM_SIMULATOR:-host.docker.internal}"

if [[ ! "${expected_delay_ms}" =~ ^[0-9]+$ ]] || (( expected_delay_ms < 5 )); then
  echo "EXPECTED_DELAY_MS must be an integer greater than or equal to 5" >&2
  exit 1
fi

minimum_delay_ms="${MINIMUM_DELAY_MS:-$((expected_delay_ms - 5))}"
if [[ ! "${minimum_delay_ms}" =~ ^[0-9]+$ ]]; then
  echo "MINIMUM_DELAY_MS must be a non-negative integer" >&2
  exit 1
fi

for required_command in curl python3; do
  if ! command -v "${required_command}" >/dev/null 2>&1; then
    echo "required command is missing: ${required_command}" >&2
    exit 1
  fi
done

verification_tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/kakao-simulator-contract.XXXXXX")"
webhook_receiver_pid=''
temp_timeline_mapping_id='02760000-0000-4000-8000-0000000000f1'
temp_user_memory_mapping_id='02760000-0000-4000-8000-0000000000f2'

stop_webhook_receiver() {
  if [[ -n "${webhook_receiver_pid}" ]] && kill -0 "${webhook_receiver_pid}" 2>/dev/null; then
    kill "${webhook_receiver_pid}" 2>/dev/null || true
    wait "${webhook_receiver_pid}" 2>/dev/null || true
  fi
  webhook_receiver_pid=''
}

cleanup() {
  stop_webhook_receiver
  curl --silent --output /dev/null --request DELETE \
    "${simulator_base_url}/__admin/mappings/${temp_timeline_mapping_id}" || true
  curl --silent --output /dev/null --request DELETE \
    "${simulator_base_url}/__admin/mappings/${temp_user_memory_mapping_id}" || true
  rm -rf "${verification_tmp_dir}"
}
trap cleanup EXIT

response_body=''
response_headers=''
response_status=''
response_elapsed_seconds=''

perform_request() {
  local request_name="$1"
  shift

  response_body="${verification_tmp_dir}/${request_name}.body"
  response_headers="${verification_tmp_dir}/${request_name}.headers"

  local metrics
  metrics="$(curl --silent --show-error \
    --output "${response_body}" \
    --dump-header "${response_headers}" \
    --write-out '%{http_code} %{time_total}' \
    "$@")"
  response_status="${metrics%% *}"
  response_elapsed_seconds="${metrics#* }"
}

assert_status() {
  local expected_status="$1"
  local request_name="$2"
  if [[ "${response_status}" != "${expected_status}" ]]; then
    echo "${request_name}: expected HTTP ${expected_status}, got ${response_status}" >&2
    echo "response body:" >&2
    sed -n '1,80p' "${response_body}" >&2
    exit 1
  fi
}

assert_json_content_type() {
  local request_name="$1"
  local content_type
  content_type="$(python3 - "${response_headers}" <<'PY'
import sys

value = ""
with open(sys.argv[1], encoding="iso-8859-1") as headers:
    for line in headers:
        name, separator, candidate = line.partition(":")
        if separator and name.lower() == "content-type":
            value = candidate.strip()
print(value)
PY
)"

  case "${content_type}" in
    application/json*) ;;
    *)
      echo "${request_name}: expected JSON content type, got '${content_type}'" >&2
      exit 1
      ;;
  esac
}

assert_minimum_delay() {
  local request_name="$1"
  python3 - "${response_elapsed_seconds}" "${minimum_delay_ms}" "${request_name}" <<'PY'
import sys

elapsed_ms = float(sys.argv[1]) * 1000
minimum_ms = int(sys.argv[2])
if elapsed_ms < minimum_ms:
    raise SystemExit(
        f"{sys.argv[3]}: expected at least {minimum_ms} ms, got {elapsed_ms:.2f} ms"
    )
PY
}

assert_json_shape() {
  local shape="$1"
  local expected_value="${2:-}"
  python3 - "${response_body}" "${shape}" "${expected_value}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as response:
    payload = json.load(response)

shape = sys.argv[2]
expected_value = sys.argv[3]

if shape == "health":
    assert payload.get("status") == "healthy", payload
    assert payload.get("version") == "3.13.2", payload
elif shape == "coord":
    documents = payload.get("documents")
    assert isinstance(documents, list) and len(documents) == 1, payload
    document = documents[0]
    assert document["road_address"]["address_name"] == "서울 테스트구 시뮬레이터로 251"
    assert document["road_address"]["building_name"] == "Laimory 테스트빌딩"
    assert document["address"]["address_name"] == "서울 테스트구 테스트동 251"
elif shape == "keyword":
    documents = payload.get("documents")
    assert isinstance(documents, list), payload
    assert [document.get("place_name") for document in documents] == [
        "Laimory 테스트카페",
        "Laimory 테스트식당",
    ]
elif shape == "ai_dispatch":
    assert payload == {"taskId": expected_value, "status": "PROCESSING"}, payload
elif shape == "count":
    assert payload.get("count") == int(expected_value), payload
elif shape == "unmatched":
    requests = payload.get("requests")
    assert isinstance(requests, list) and len(requests) == int(expected_value), payload
else:
    raise AssertionError(f"unknown assertion shape: {shape}")
PY
}

coord_request() {
  local request_name="$1"
  shift
  perform_request "${request_name}" --get \
    "${simulator_base_url}/v2/local/geo/coord2address.json" \
    "$@"
}

keyword_request() {
  local request_name="$1"
  shift
  perform_request "${request_name}" --get \
    "${simulator_base_url}/v2/local/search/keyword.json" \
    "$@"
}

valid_coord_request() {
  local request_name="$1"
  coord_request "${request_name}" \
    --header "Authorization: ${authorization}" \
    --data 'x=126.9668' \
    --data 'y=37.534'
}

valid_keyword_request() {
  local request_name="$1"
  keyword_request "${request_name}" \
    --header "Authorization: ${authorization}" \
    --data-urlencode 'query=서울 테스트구 시뮬레이터로 251' \
    --data 'x=126.9668' \
    --data 'y=37.534' \
    --data 'radius=50' \
    --data 'sort=distance'
}

new_uuid() {
  python3 -c 'import uuid; print(uuid.uuid4())'
}

epoch_now() {
  python3 -c 'import time; print(time.time())'
}

ai_dispatch_request() {
  local request_name="$1"
  local dispatch_path="$2"
  local dispatch_body="$3"
  perform_request "${request_name}" \
    --request POST \
    --header 'Content-Type: application/json' \
    --data "${dispatch_body}" \
    "${simulator_base_url}${dispatch_path}"
}

valid_timeline_dispatch_body() {
  local task_id="$1"
  local task_token="$2"
  printf '{"taskId":"%s","taskToken":"%s","dailyRecordId":251,"window":{"startAt":"2026-01-01T00:00:00","endAt":"2026-01-02T00:00:00"}}' \
    "${task_id}" "${task_token}"
}

valid_user_memory_dispatch_body() {
  local task_id="$1"
  local task_token="$2"
  printf '{"taskId":"%s","taskToken":"%s","dailyTimelines":[{"date":"2026-01-01","events":[]}]}' \
    "${task_id}" "${task_token}"
}

webhook_received_file="${verification_tmp_dir}/webhook-received.jsonl"

start_webhook_receiver() {
  cat > "${verification_tmp_dir}/webhook-receiver.py" <<'PY'
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, required=True)
parser.add_argument("--output", required=True)
args = parser.parse_args()


class WebhookHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length).decode("utf-8")
        record = {
            "received_at": time.time(),
            "method": self.command,
            "path": self.path,
            "headers": dict(self.headers.items()),
            "body": body,
        }
        with open(args.output, "a", encoding="utf-8") as output:
            output.write(json.dumps(record) + "\n")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, *_):
        pass


HTTPServer(("0.0.0.0", args.port), WebhookHandler).serve_forever()
PY

  : > "${webhook_received_file}"
  python3 "${verification_tmp_dir}/webhook-receiver.py" \
    --port "${webhook_receiver_port}" \
    --output "${webhook_received_file}" &
  webhook_receiver_pid=$!

  local attempt
  for attempt in $(seq 1 50); do
    if curl --silent --output /dev/null "http://127.0.0.1:${webhook_receiver_port}/"; then
      return 0
    fi
    if ! kill -0 "${webhook_receiver_pid}" 2>/dev/null; then
      echo "webhook receiver exited before becoming ready (port ${webhook_receiver_port} in use?)" >&2
      exit 1
    fi
    sleep 0.1
  done
  echo "webhook receiver did not become ready on port ${webhook_receiver_port}" >&2
  exit 1
}

register_temp_webhook_mapping() {
  local request_name="$1"
  local source_mapping_file="$2"
  local temp_mapping_id="$3"
  local temp_mapping_file="${verification_tmp_dir}/${request_name}.json"

  python3 - "${source_mapping_file}" "${temp_mapping_id}" \
    "http://${webhook_host_from_simulator}:${webhook_receiver_port}" \
    > "${temp_mapping_file}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    mapping = json.load(source)

mapping["id"] = sys.argv[2]
mapping["name"] = mapping["name"] + "-webhook-verification"
webhook_parameters = mapping["serveEventListeners"][0]["parameters"]
original_path = webhook_parameters["url"].split("/", 3)[3]
webhook_parameters["url"] = sys.argv[3].rstrip("/") + "/" + original_path
# 검증용 임시 mapping은 지연을 2초로 override한다. production mapping의 지연(60초)은 운영값이라
# 보존하되, C13이 재는 것은 지연값이 아니라 webhook 전달 메커니즘(URL 파생·header·body)이다 —
# production 지연을 그대로 복사하면 아래 10초 대기·1.5~8초 판정과 양립하지 않아 확정 실패한다.
webhook_parameters["delay"] = {"type": "fixed", "milliseconds": 2000}
json.dump(mapping, sys.stdout)
PY

  perform_request "${request_name}" \
    --request POST \
    --header 'Content-Type: application/json' \
    --data-binary "@${temp_mapping_file}" \
    "${simulator_base_url}/__admin/mappings"
  assert_status 201 "${request_name}"
}

delete_temp_webhook_mapping() {
  local request_name="$1"
  local temp_mapping_id="$2"
  perform_request "${request_name}" --request DELETE \
    "${simulator_base_url}/__admin/mappings/${temp_mapping_id}"
  assert_status 200 "${request_name}"
}

wait_for_webhook_lines() {
  python3 - "${webhook_received_file}" "$1" <<'PY'
import sys
import time

path, expected = sys.argv[1], int(sys.argv[2])
deadline = time.time() + 10
while time.time() < deadline:
    try:
        with open(path, encoding="utf-8") as received:
            lines = [line for line in received if line.strip()]
        if len(lines) >= expected:
            sys.exit(0)
    except FileNotFoundError:
        pass
    time.sleep(0.1)
sys.exit(f"webhook receiver did not get {expected} request(s) within 10s")
PY
}

assert_webhook_delivery() {
  local request_name="$1"
  local line_index="$2"
  local expected_path="$3"
  local expected_token="$4"
  local expected_body="$5"
  local dispatch_done_epoch="$6"
  python3 - "${webhook_received_file}" "${line_index}" "${expected_path}" \
    "${expected_token}" "${expected_body}" "${dispatch_done_epoch}" "${request_name}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as received:
    lines = [line for line in received if line.strip()]
record = json.loads(lines[int(sys.argv[2])])
request_name = sys.argv[7]

assert record["method"] == "POST", record
assert record["path"] == sys.argv[3], record
headers = {name.lower(): value for name, value in record["headers"].items()}
assert headers.get("task-token") == sys.argv[4], record
assert headers.get("content-type", "").startswith("application/json"), record
assert json.loads(record["body"]) == json.loads(sys.argv[5]), record

delay_ms = (record["received_at"] - float(sys.argv[6])) * 1000
assert 1500 <= delay_ms <= 8000, (
    f"{request_name}: expected ~2000 ms webhook delay, got {delay_ms:.0f} ms"
)
print(f"{request_name}: webhook delivered after {delay_ms:.0f} ms")
PY
}

echo "C1 health and WireMock version"
perform_request health "${simulator_base_url}/__admin/health"
assert_status 200 C1
assert_json_content_type C1
assert_json_shape health

echo "C2 coord2address success contract"
valid_coord_request coord_success
assert_status 200 C2
assert_json_content_type C2
assert_json_shape coord
assert_minimum_delay C2

echo "C3 keyword success contract"
valid_keyword_request keyword_success
assert_status 200 C3
assert_json_content_type C3
assert_json_shape keyword
assert_minimum_delay C3

echo "C4 Authorization mismatch"
coord_request coord_missing_authorization \
  --data 'x=126.9668' \
  --data 'y=37.534'
assert_status 404 C4-missing
coord_request coord_wrong_authorization \
  --header 'Authorization: KakaoAK wrong' \
  --data 'x=126.9668' \
  --data 'y=37.534'
assert_status 404 C4-wrong

echo "C5 invalid coord query"
coord_request coord_missing_x \
  --header "Authorization: ${authorization}" \
  --data 'y=37.534'
assert_status 404 C5-missing
coord_request coord_invalid_y \
  --header "Authorization: ${authorization}" \
  --data 'x=126.9668' \
  --data 'y=not-a-coordinate'
assert_status 404 C5-invalid

echo "C6 invalid keyword query"
keyword_request keyword_missing_query \
  --header "Authorization: ${authorization}" \
  --data 'x=126.9668' \
  --data 'y=37.534' \
  --data 'radius=50' \
  --data 'sort=distance'
assert_status 404 C6-query
keyword_request keyword_wrong_radius \
  --header "Authorization: ${authorization}" \
  --data-urlencode 'query=서울 테스트구 시뮬레이터로 251' \
  --data 'x=126.9668' \
  --data 'y=37.534' \
  --data 'radius=51' \
  --data 'sort=distance'
assert_status 404 C6-radius
keyword_request keyword_wrong_sort \
  --header "Authorization: ${authorization}" \
  --data-urlencode 'query=서울 테스트구 시뮬레이터로 251' \
  --data 'x=126.9668' \
  --data 'y=37.534' \
  --data 'radius=50' \
  --data 'sort=accuracy'
assert_status 404 C6-sort

echo "C7 unsupported optional keyword query"
for optional_query in 'category_group_code=CE7' 'rect=126,37,127,38' 'page=1' 'size=15'; do
  request_suffix="${optional_query%%=*}"
  keyword_request "keyword_optional_${request_suffix}" \
    --header "Authorization: ${authorization}" \
    --data-urlencode 'query=서울 테스트구 시뮬레이터로 251' \
    --data 'x=126.9668' \
    --data 'y=37.534' \
    --data 'radius=50' \
    --data 'sort=distance' \
    --data "${optional_query}"
  assert_status 404 "C7-${request_suffix}"
done

echo "C8 request journal count and unmatched verification"
perform_request journal_reset --request DELETE "${simulator_base_url}/__admin/requests"
assert_status 200 C8-reset

valid_coord_request coord_counted
assert_status 200 C8-coord
valid_keyword_request keyword_counted
assert_status 200 C8-keyword

perform_request coord_count \
  --request POST \
  --header 'Content-Type: application/json' \
  --data '{"method":"GET","urlPath":"/v2/local/geo/coord2address.json"}' \
  "${simulator_base_url}/__admin/requests/count"
assert_status 200 C8-coord-count
assert_json_shape count 1

perform_request keyword_count \
  --request POST \
  --header 'Content-Type: application/json' \
  --data '{"method":"GET","urlPath":"/v2/local/search/keyword.json"}' \
  "${simulator_base_url}/__admin/requests/count"
assert_status 200 C8-keyword-count
assert_json_shape count 1

perform_request unmatched "${simulator_base_url}/__admin/requests/unmatched"
assert_status 200 C8-unmatched
assert_json_shape unmatched 0

echo "C9 AI timeline dispatch 202 echo contract"
timeline_task_id="$(new_uuid)"
timeline_task_token="token-$(new_uuid)"
ai_dispatch_request timeline_dispatch_success /v1/timeline \
  "$(valid_timeline_dispatch_body "${timeline_task_id}" "${timeline_task_token}")"
assert_status 202 C9
assert_json_content_type C9
assert_json_shape ai_dispatch "${timeline_task_id}"
assert_minimum_delay C9

echo "C10 AI timeline invalid dispatch body"
ai_dispatch_request timeline_missing_task_id /v1/timeline \
  '{"taskToken":"token","dailyRecordId":251,"window":{"startAt":"2026-01-01T00:00:00","endAt":"2026-01-02T00:00:00"}}'
assert_status 404 C10-taskId
ai_dispatch_request timeline_missing_task_token /v1/timeline \
  '{"taskId":"task","dailyRecordId":251,"window":{"startAt":"2026-01-01T00:00:00","endAt":"2026-01-02T00:00:00"}}'
assert_status 404 C10-taskToken
ai_dispatch_request timeline_missing_daily_record_id /v1/timeline \
  '{"taskId":"task","taskToken":"token","window":{"startAt":"2026-01-01T00:00:00","endAt":"2026-01-02T00:00:00"}}'
assert_status 404 C10-dailyRecordId
ai_dispatch_request timeline_missing_window_start_at /v1/timeline \
  '{"taskId":"task","taskToken":"token","dailyRecordId":251,"window":{"endAt":"2026-01-02T00:00:00"}}'
assert_status 404 C10-window-startAt

echo "C11 AI user-memory dispatch 202 echo contract"
user_memory_task_id="$(new_uuid)"
user_memory_task_token="token-$(new_uuid)"
ai_dispatch_request user_memory_dispatch_success /v1/user-memory \
  "$(valid_user_memory_dispatch_body "${user_memory_task_id}" "${user_memory_task_token}")"
assert_status 202 C11
assert_json_content_type C11
assert_json_shape ai_dispatch "${user_memory_task_id}"
assert_minimum_delay C11

echo "C12 AI user-memory invalid dispatch body"
ai_dispatch_request user_memory_missing_task_token /v1/user-memory \
  '{"taskId":"task","dailyTimelines":[{"date":"2026-01-01","events":[]}]}'
assert_status 404 C12-taskToken
ai_dispatch_request user_memory_missing_daily_timelines /v1/user-memory \
  '{"taskId":"task","taskToken":"token"}'
assert_status 404 C12-dailyTimelines

echo "C13 AI webhook delivery verification"
start_webhook_receiver
register_temp_webhook_mapping timeline_temp_mapping \
  "${mappings_dir}/ai-timeline.json" "${temp_timeline_mapping_id}"
register_temp_webhook_mapping user_memory_temp_mapping \
  "${mappings_dir}/ai-user-memory.json" "${temp_user_memory_mapping_id}"

webhook_timeline_task_id="$(new_uuid)"
webhook_timeline_task_token="token-$(new_uuid)"
ai_dispatch_request timeline_webhook_dispatch /v1/timeline \
  "$(valid_timeline_dispatch_body "${webhook_timeline_task_id}" "${webhook_timeline_task_token}")"
assert_status 202 C13-timeline-dispatch
assert_json_shape ai_dispatch "${webhook_timeline_task_id}"
timeline_dispatch_done_epoch="$(epoch_now)"

wait_for_webhook_lines 1
assert_webhook_delivery C13-timeline 0 \
  "/s/api/v1/timeline/drafts/${webhook_timeline_task_id}/callback" \
  "${webhook_timeline_task_token}" \
  '{"status":"FAILED","errorCode":-1008,"error":"kakao-simulator fake AI"}' \
  "${timeline_dispatch_done_epoch}"

webhook_user_memory_task_id="$(new_uuid)"
webhook_user_memory_task_token="token-$(new_uuid)"
ai_dispatch_request user_memory_webhook_dispatch /v1/user-memory \
  "$(valid_user_memory_dispatch_body "${webhook_user_memory_task_id}" "${webhook_user_memory_task_token}")"
assert_status 202 C13-user-memory-dispatch
assert_json_shape ai_dispatch "${webhook_user_memory_task_id}"
user_memory_dispatch_done_epoch="$(epoch_now)"

wait_for_webhook_lines 2
assert_webhook_delivery C13-user-memory 1 \
  "/s/api/v1/user-memory/updates/${webhook_user_memory_task_id}/result" \
  "${webhook_user_memory_task_token}" \
  '{"status":"SUCCESS","userMemory":{"schemaVersion":"1.0","updatedAt":"2026-01-01T00:00:00Z","currentFocus":"[SIMULATOR] fake user memory"},"errorCode":null,"error":null}' \
  "${user_memory_dispatch_done_epoch}"

delete_temp_webhook_mapping timeline_temp_mapping_delete "${temp_timeline_mapping_id}"
delete_temp_webhook_mapping user_memory_temp_mapping_delete "${temp_user_memory_mapping_id}"
stop_webhook_receiver

echo "C14 AI dispatch journal count and unmatched verification"
perform_request ai_journal_reset --request DELETE "${simulator_base_url}/__admin/requests"
assert_status 200 C14-reset

ai_dispatch_request timeline_counted /v1/timeline \
  "$(valid_timeline_dispatch_body "$(new_uuid)" "token-$(new_uuid)")"
assert_status 202 C14-timeline
ai_dispatch_request user_memory_counted /v1/user-memory \
  "$(valid_user_memory_dispatch_body "$(new_uuid)" "token-$(new_uuid)")"
assert_status 202 C14-user-memory

perform_request timeline_count \
  --request POST \
  --header 'Content-Type: application/json' \
  --data '{"method":"POST","urlPath":"/v1/timeline"}' \
  "${simulator_base_url}/__admin/requests/count"
assert_status 200 C14-timeline-count
assert_json_shape count 1

perform_request user_memory_count \
  --request POST \
  --header 'Content-Type: application/json' \
  --data '{"method":"POST","urlPath":"/v1/user-memory"}' \
  "${simulator_base_url}/__admin/requests/count"
assert_status 200 C14-user-memory-count
assert_json_shape count 1

perform_request ai_unmatched "${simulator_base_url}/__admin/requests/unmatched"
assert_status 200 C14-unmatched
assert_json_shape unmatched 0

echo "Kakao simulator contract verification passed, fake AI paths included (${simulator_base_url})"
