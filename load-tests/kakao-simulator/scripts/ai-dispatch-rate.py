#!/usr/bin/env python3
"""가짜 AI(WireMock) journal에서 AI 접수 도착 시각을 읽어 송신률을 판정한다(#515).

서버의 세션 생성 limiter가 지키려는 것은 "AgentCore가 받는 접수가 어느 1초에도 25건을 넘지 않는다"이다.
test 환경에서는 AgentCore 대신 이 simulator가 접수를 받으므로, journal의 수신 시각(simulator 시계 하나)이
곧 AgentCore가 보게 될 도착 분포다.

사용:
  curl -s http://127.0.0.1:18080/__admin/requests > load-tests/timeline-draft/.artifacts/journal.json
  python3 load-tests/kakao-simulator/scripts/ai-dispatch-rate.py \
      load-tests/timeline-draft/.artifacts/journal.json --expected 300

판정(하나라도 어기면 종료 코드 1):
  - 어느 1초 구간 [t, t+1000ms)에 도착한 접수 수 <= --limit(기본 25)
  - 같은 taskId가 두 번 도착하지 않음(중복 접수 없음)
  - --expected를 주면 도착 수 == expected

journal에는 요청 body(taskToken 포함)가 들어 있다. 이 스크립트는 taskId도 출력하지 않는다. journal 파일은
.artifacts/에만 두고 run이 끝나면 지운다.
"""

import argparse
import json
import sys
from datetime import datetime, timezone

WINDOW_MILLIS = 1000


def load_requests(path):
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    if isinstance(doc, dict):
        if doc.get("requestJournalDisabled"):
            sys.exit("journal이 꺼져 있다(--no-request-journal). simulator 설정을 확인한다.")
        items = doc.get("requests", [])
    else:
        items = doc
    for item in items:
        # GET /__admin/requests는 {"request": {...}} 묶음이고, /__admin/requests/find는 요청 그 자체다.
        yield item.get("request", item)


def task_id_of(request):
    try:
        return json.loads(request.get("body") or "{}").get("taskId")
    except (ValueError, AttributeError):
        return None


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
    parser = argparse.ArgumentParser(description="simulator journal로 AI 접수 송신률을 판정한다(#515).")
    parser.add_argument("journal", help="GET /__admin/requests 응답을 저장한 JSON 파일")
    parser.add_argument("--path", default="/v1/timeline", help="세는 접수 경로(기본 /v1/timeline)")
    parser.add_argument("--limit", type=int, default=25, help="어느 1초 구간의 허용 상한(기본 25)")
    parser.add_argument("--expected", type=int, help="도착해야 할 접수 수(k6에서 202를 받은 수)")
    args = parser.parse_args()

    times, per_task = [], {}
    for request in load_requests(args.journal):
        if request.get("method") != "POST" or (request.get("url") or "").split("?")[0] != args.path:
            continue
        if request.get("loggedDate") is None:
            sys.exit("loggedDate가 없는 요청이 있다 — WireMock journal 형식이 예상과 다르다.")
        times.append(int(request["loggedDate"]))
        task_id = task_id_of(request)
        if task_id:
            per_task[task_id] = per_task.get(task_id, 0) + 1

    failures = []
    print(f"경로: POST {args.path}")
    print(f"도착 수: {len(times)}")
    if args.expected is not None and len(times) != args.expected:
        failures.append(f"도착 수 {len(times)} != 기대 {args.expected}")

    if times:
        times.sort()
        span = (times[-1] - times[0]) / 1000
        peak, peak_start = max_in_any_window(times)
        print(f"첫 도착: {iso(times[0])}")
        print(f"마지막 도착: {iso(times[-1])}")
        print(f"걸린 시간: {span:.1f}초")
        if span > 0:
            print(f"평균 송신률: {(len(times) - 1) / span:.1f}건/초")
        print(f"어느 1초 최대: {peak}건 (구간 시작 {iso(peak_start)}, 상한 {args.limit})")
        if peak > args.limit:
            failures.append(f"어느 1초 최대 {peak}건 > 상한 {args.limit}")

        print("초별 도착(첫 도착 기준):")
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
