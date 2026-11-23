#!/usr/bin/env python3
"""AI 접수 시각을 읽어 송신률을 판정한다(#515).

서버의 세션 생성 limiter가 지키려는 것은 "AgentCore가 받는 접수가 어느 1초에도 25건을 넘지 않는다"이다.
입력은 둘 중 하나다.

- 앱 로그(기본, test 환경 `APP_AI_MODE=noop`): worker가 접수를 마친 직후 남기는
  `timeline 대기열 접수 완료: taskId=…` 줄의 시각. noop은 즉시 반환하므로 이 시각이 곧 보낸 시각이다.
  JSON 로그(`@timestamp`)와 text 로그(`yyyy-MM-dd HH:mm:ss.SSS …`)를 모두 읽는다.
- `--journal`: 가짜 AI(WireMock)의 `GET /__admin/requests` 응답. 수신 시각을 쓴다.

사용(test host, 로그를 표준 입력으로):
  sudo docker logs --since 15m laimory 2>&1 | python3 ai-dispatch-rate.py - --expected 300

판정(하나라도 어기면 종료 코드 1):
  - 어느 1초 구간 [t, t+1000ms)의 접수 수 <= --limit(기본 25)
  - 같은 taskId가 두 번 접수되지 않음
  - --expected를 주면 접수 수 == expected (로그는 부하 중 유실될 수 있어 이 대조가 측정 유효성 확인이다)

taskId는 출력하지 않는다. journal 입력은 요청 body(taskToken)를 담고 있으므로 판정 뒤 파일을 지운다.
"""

import argparse
import json
import re
import sys
from datetime import datetime, timezone

WINDOW_MILLIS = 1000
DEFAULT_LOG_MARKER = "timeline 대기열 접수 완료: taskId="
TASK_ID = re.compile(r"taskId=([0-9A-Za-z-]+)")
TEXT_TIMESTAMP = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})")
FRACTION = re.compile(r"\.(\d+)")


def open_source(source):
    return sys.stdin if source == "-" else open(source, encoding="utf-8", errors="replace")


def parse_iso_millis(value):
    # Python 3.11 이전 fromisoformat은 소수 3·6자리만 받는다 — logback은 7자리까지 찍으므로 6자리로 맞춘다.
    value = FRACTION.sub(lambda m: "." + (m.group(1) + "000000")[:6], value, count=1)
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return int(parsed.timestamp() * 1000)


def read_log(source, marker):
    """(epoch millis, taskId) — marker가 든 줄만."""
    with open_source(source) as lines:
        for line in lines:
            if marker not in line:
                continue
            line = line.strip()
            message, millis = line, None
            if line.startswith("{"):
                try:
                    event = json.loads(line)
                    message = event.get("message", "")
                    millis = parse_iso_millis(event["@timestamp"])
                except (ValueError, KeyError):
                    millis = None
            else:
                matched = TEXT_TIMESTAMP.match(line)
                if matched:
                    millis = parse_iso_millis(matched.group(1).replace(" ", "T"))
            if millis is None or marker not in message:
                continue
            task = TASK_ID.search(message)
            yield millis, task.group(1) if task else None


def read_journal(source, path):
    """(epoch millis, taskId) — POST {path} 요청만."""
    with open_source(source) as f:
        doc = json.load(f)
    if isinstance(doc, dict):
        if doc.get("requestJournalDisabled"):
            sys.exit("journal이 꺼져 있다(--no-request-journal). simulator 설정을 확인한다.")
        items = doc.get("requests", [])
    else:
        items = doc
    for item in items:
        # GET /__admin/requests는 {"request": {...}} 묶음이고, /__admin/requests/find는 요청 그 자체다.
        request = item.get("request", item)
        if request.get("method") != "POST" or (request.get("url") or "").split("?")[0] != path:
            continue
        if request.get("loggedDate") is None:
            sys.exit("loggedDate가 없는 요청이 있다 — WireMock journal 형식이 예상과 다르다.")
        try:
            task_id = json.loads(request.get("body") or "{}").get("taskId")
        except (ValueError, AttributeError):
            task_id = None
        yield int(request["loggedDate"]), task_id


def iso(epoch_millis):
    return datetime.fromtimestamp(epoch_millis / 1000, tz=timezone.utc).isoformat(timespec="milliseconds")


def max_in_any_window(sorted_times):
    """반열린 구간 [t, t+1000)에 드는 최대 개수와 그 구간 시작 시각."""
    best, best_start, start = 0, None, 0
    for end, t in enumerate(sorted_times):
        while t - sorted_times[start] >= WINDOW_MILLIS:
            start += 1
        if end - start + 1 > best:
            best, best_start = end - start + 1, sorted_times[start]
    return best, best_start


def main():
    parser = argparse.ArgumentParser(description="AI 접수 시각으로 송신률을 판정한다(#515).")
    parser.add_argument("source", help="앱 로그 파일 또는 '-'(표준 입력). --journal이면 journal JSON 파일")
    parser.add_argument("--journal", action="store_true", help="가짜 AI(WireMock) journal을 읽는다")
    parser.add_argument("--path", default="/v1/timeline", help="journal에서 세는 접수 경로(기본 /v1/timeline)")
    parser.add_argument("--marker", default=DEFAULT_LOG_MARKER, help="로그에서 세는 문구")
    parser.add_argument("--limit", type=int, default=25, help="어느 1초 구간의 허용 상한(기본 25)")
    parser.add_argument("--expected", type=int, help="접수돼야 할 수(k6에서 202를 받은 수)")
    args = parser.parse_args()

    events = list(read_journal(args.source, args.path) if args.journal else read_log(args.source, args.marker))
    times = sorted(millis for millis, _ in events)
    per_task = {}
    for _, task_id in events:
        if task_id:
            per_task[task_id] = per_task.get(task_id, 0) + 1

    failures = []
    print(f"입력: {'journal POST ' + args.path if args.journal else '로그 ' + repr(args.marker)}")
    print(f"접수 수: {len(times)}")
    if args.expected is not None and len(times) != args.expected:
        failures.append(f"접수 수 {len(times)} != 기대 {args.expected}")

    if times:
        span = (times[-1] - times[0]) / 1000
        peak, peak_start = max_in_any_window(times)
        print(f"첫 접수: {iso(times[0])}")
        print(f"마지막 접수: {iso(times[-1])}")
        print(f"걸린 시간: {span:.1f}초")
        if span > 0:
            print(f"평균 송신률: {(len(times) - 1) / span:.1f}건/초")
        print(f"어느 1초 최대: {peak}건 (구간 시작 {iso(peak_start)}, 상한 {args.limit})")
        if peak > args.limit:
            failures.append(f"어느 1초 최대 {peak}건 > 상한 {args.limit}")

        print("초별 접수(첫 접수 기준):")
        buckets = {}
        for t in times:
            second = (t - times[0]) // WINDOW_MILLIS
            buckets[second] = buckets.get(second, 0) + 1
        for second in range(max(buckets) + 1):
            print(f"  +{second:>3}s {buckets.get(second, 0):>3}")

    duplicated = sum(1 for count in per_task.values() if count > 1)
    print(f"중복 taskId: {duplicated}")
    if duplicated:
        failures.append(f"같은 task가 두 번 이상 접수됨: {duplicated}개")

    if failures:
        print("판정: FAIL")
        for failure in failures:
            print(f"  - {failure}")
        sys.exit(1)
    print("판정: PASS")


if __name__ == "__main__":
    main()
