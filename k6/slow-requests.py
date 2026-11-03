# 부하 중에 남은 로그에서 느렸던 요청을 찾아 표로 낸다
#   slow-requests.py nginx nginx.log [--app app.log] [--k6 흐름요약.json] [--queue 줄서기요약.json:API=시나리오 ...] [--top N]
#   slow-requests.py app   app.log                    앱의 WARN, ERROR와 느린 요청 로그
#   slow-requests.py pg    postgres.log               Postgres의 느린 쿼리와 잠금 대기
# nginx 로그는 docker/nginx/nginx.conf의 JSON 형식(느린 요청과 서버 오류만 남음)이다.
# 기준 시간(100ms) 아래 요청은 로그에 없으므로, 전체 요청 수는 k6 요약에서 가져와 SLO p95를 넘은 비율을 계산한다
import argparse
import collections
import importlib.util
import json
import pathlib
import re
import statistics
import sys

spec = importlib.util.spec_from_file_location("slo_check", pathlib.Path(__file__).with_name("slo-check.py"))
slo_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(slo_check)
SLO = slo_check.SLO

ROUTES = [
    (r"^/api/events/[^/]+/queues/me$", "poll"),
    (r"^/api/events/[^/]+/queues$", "register"),
    (r"^/api/events/[^/]+/seats/status$", "seat_status"),
    (r"^/api/events/[^/]+/seats/layout$", "seat_layout"),
    (r"^/api/events/[^/]+/seats/hold$", "hold"),
    (r"^/api/events/[^/]+$", "events_detail"),
    (r"^/api/events$", "events_list"),
    (r"^/api/reservations/[^/]+/payments$", "pay"),
    (r"^/api/reservations/lookup$", "lookup"),
    (r"^/api/reservations/cancel$", "cancel"),
]
WINDOW_SECONDS = 5


def api_of(uri):
    for pattern, api in ROUTES:
        if re.match(pattern, uri):
            return api
    return "other"


def seconds_of(value):
    # "0.012", "-", "0.001, 0.002"(재시도), "0.001 : 0.002"(내부 리다이렉트)처럼 올 수 있다
    parts = [p for p in re.split(r"[,:]", value or "") if p.strip() not in ("", "-")]
    return sum(float(p) for p in parts) if parts else None


def read_json_lines(path):
    out = []
    for line in open(path, errors="replace"):
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    return out


def totals_from_k6(flow_summary, queue_specs):
    totals = {}
    if flow_summary:
        metrics = json.load(open(flow_summary))["metrics"]
        for api in SLO:
            good = metrics.get(f"sli_good{{api:{api}}}")
            if good:
                excluded = metrics.get(f"sli_excluded{{api:{api}}}", {}).get("count", 0)
                totals[api] = int(good["passes"] + good["fails"] + excluded)
    for spec in queue_specs or []:
        path, _, pair = spec.rpartition(":")
        api, _, scenario = pair.partition("=")
        reqs = json.load(open(path))["metrics"].get(f"http_reqs{{scenario:{scenario}}}")
        if reqs:
            totals[api] = int(reqs["count"])
    return totals


def clock(iso):
    # 2026-11-03T12:15:14+00:00 -> 12:15:14
    m = re.search(r"T(\d\d:\d\d:\d\d)", iso)
    return m.group(1) if m else iso


def nginx_report(args):
    rows = read_json_lines(args.log)
    if not rows:
        print("- nginx 로그에 느린 요청이나 서버 오류가 한 건도 없음(기준 100ms 이상, 500/502/504)")
        return
    app_by_id = {}
    if args.app:
        for r in read_json_lines(args.app):
            m = re.search(r"느린 요청: .*elapsedMs=(\d+)", r.get("message", ""))
            if m and r.get("traceId"):
                app_by_id[r["traceId"]] = int(m.group(1))
    totals = totals_from_k6(args.k6, args.queue)

    by_api = collections.defaultdict(list)
    for r in rows:
        r["api"] = api_of(r.get("uri", ""))
        r["ms"] = float(r["request_time"]) * 1000
        up = seconds_of(r.get("upstream_time"))
        r["upstream_ms"] = None if up is None else up * 1000
        by_api[r["api"]].append(r)

    times = sorted(r["time"] for r in rows)
    print(f"- 로그 {len(rows):,}줄, {clock(times[0])} ~ {clock(times[-1])}. 기준: 100ms 이상이거나 500, 502, 504 응답")
    print("\n| API | 느린 요청 수 | SLO p95 초과 | 전체 요청 | 초과 비율 | p95 판정(5% 이내) | 가장 느린 ms | 앱 구간이 절반 이상 | 앱 밖 구간이 절반 이상 | 5xx(503 포함) |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for api in [a for a in list(SLO) + ["other"] if a in by_api]:
        group = by_api[api]
        target = SLO[api][0] if api in SLO else None
        over = sum(1 for r in group if target is not None and r["ms"] > target)
        total = totals.get(api)
        ratio = "-" if not total or target is None else f"{over / total * 100:.2f}%"
        verdict = "-" if not total or target is None else ("넘음" if over / total > 0.05 else "안쪽")
        in_app = sum(1 for r in group if r["upstream_ms"] is not None and r["upstream_ms"] >= r["ms"] / 2)
        outside = sum(1 for r in group if r["upstream_ms"] is not None and r["upstream_ms"] < r["ms"] / 2)
        errors = sum(1 for r in group if int(r["status"]) >= 500)
        print(f"| {api} | {len(group):,} | {'-' if target is None else format(over, ',')} | {'-' if not total else format(total, ',')} | "
              f"{ratio} | {verdict} | {max(r['ms'] for r in group):.0f} | {in_app:,} | {outside:,} | {errors:,} |")
    print("- 앱 구간은 upstream_time(nginx가 앱에 보내고 응답을 받기까지), 앱 밖 구간은 나머지(요청을 받는 시간, nginx 대기, 클라이언트에 보내는 시간)다")

    print(f"\n느린 요청 상위 {args.top}건")
    print("\n| 시각 | 요청 id | API | 상태 | 총 ms | 앱 구간 ms | 앱 안에서 ms | 캐시 |")
    print("|---|---|---|---|---|---|---|---|")
    for r in sorted(rows, key=lambda x: -x["ms"])[: args.top]:
        up = "-" if r["upstream_ms"] is None else f"{r['upstream_ms']:.0f}"
        app = app_by_id.get(r["id"])
        print(f"| {clock(r['time'])} | {r['id']} | {r['api']} | {r['status']} | {r['ms']:.0f} | {up} | "
              f"{'-' if app is None else app} | {r.get('cache') or '-'} |")
    if args.app:
        matched = sum(1 for r in rows if r["id"] in app_by_id)
        print(f"- 앱 느린 요청 로그와 id가 맞은 건 {matched:,}/{len(rows):,}. 안 맞은 건은 앱 처리가 기준(앱 설정) 아래였거나 nginx가 직접 답한 요청이다")
        print("- 요청 id로 앱 로그 찾기: `docker logs <앱 컨테이너> | grep <요청 id>`")

    windows = collections.Counter()
    window_apis = collections.defaultdict(collections.Counter)
    for r in rows:
        m = re.search(r"T(\d\d):(\d\d):(\d\d)", r["time"])
        if not m:
            continue
        sec = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
        key = sec - sec % WINDOW_SECONDS
        windows[key] += 1
        window_apis[key][r["api"]] += 1
    print(f"\n느린 요청이 몰린 시간대({WINDOW_SECONDS}초 단위, 상위 5개)")
    print("\n| 시작 시각 | 건수 | 가장 많은 API |")
    print("|---|---|---|")
    for key, count in windows.most_common(5):
        api, n = window_apis[key].most_common(1)[0]
        print(f"| {key // 3600:02d}:{key % 3600 // 60:02d}:{key % 60:02d} | {count:,} | {api} {n:,}건 |")
    print(f"- 전체 {len(windows)}개 시간대에 느린 요청이 있었다. 한두 시간대에 몰렸으면 그 순간의 사건(GC, 스케줄러, 캐시 갱신)을, 고르게 퍼졌으면 처리 용량 부족을 의심한다")


def app_report(args):
    rows = read_json_lines(args.log)
    levels = collections.Counter(r.get("level") for r in rows)
    print(f"- 앱 로그 {len(rows):,}줄: WARN {levels['WARN']:,}, ERROR {levels['ERROR']:,}")
    slow = collections.defaultdict(list)
    messages = collections.Counter()
    leaks = 0
    for r in rows:
        message = r.get("message", "")
        if "Apparent connection leak" in message:
            leaks += 1
        m = re.search(r"느린 요청: method=(\w+), uri=(\S+), status=(\S+), elapsedMs=(\d+)", message)
        if m:
            slow[api_of(m.group(2))].append(int(m.group(4)))
        elif r.get("level") in ("WARN", "ERROR"):
            messages[(r["level"], re.sub(r"\d+", "N", message)[:110])] += 1
    if slow:
        print("\n| API | 앱 안에서 느린 요청 | 가장 느린 ms | 중앙값 ms |")
        print("|---|---|---|---|")
        for api, values in sorted(slow.items(), key=lambda kv: -len(kv[1])):
            print(f"| {api} | {len(values):,} | {max(values)} | {statistics.median(values):.0f} |")
    else:
        print("- 앱 안에서 기준보다 오래 걸린 요청 로그 없음")
    if messages:
        print("\n| 수준 | 건수 | 메시지(숫자는 N으로 묶음) |")
        print("|---|---|---|")
        for (level, message), count in messages.most_common(8):
            print(f"| {level} | {count:,} | {message} |")
    print(f"- Hikari 연결 누수 의심(5초 넘게 쥠) 로그 {leaks}건")


def pg_report(args):
    durations = []
    locks = 0
    for line in open(args.log, errors="replace"):
        m = re.search(r"duration: ([\d.]+) ms\s+(?:statement|execute [^:]*|parse [^:]*|bind [^:]*): (.*)", line)
        if m:
            sql = re.sub(r"'[^']*'", "'?'", m.group(2))
            sql = re.sub(r"\b\d+\b", "N", sql)
            durations.append((float(m.group(1)), re.sub(r"\s+", " ", sql)[:100]))
        if "still waiting for" in line:
            locks += 1
    print(f"- 100ms 이상 걸린 쿼리 {len(durations):,}건, 잠금 대기 로그 {locks}건")
    if not durations:
        return
    grouped = collections.defaultdict(list)
    for ms, sql in durations:
        grouped[sql].append(ms)
    print("\n| 건수 | 가장 느린 ms | 중앙값 ms | 쿼리(값은 가림) |")
    print("|---|---|---|---|")
    for sql, values in sorted(grouped.items(), key=lambda kv: -max(kv[1]))[:6]:
        print(f"| {len(values):,} | {max(values):.0f} | {statistics.median(values):.0f} | {sql} |")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="mode", required=True)
    n = sub.add_parser("nginx")
    n.add_argument("log")
    n.add_argument("--app")
    n.add_argument("--k6")
    n.add_argument("--queue", action="append")
    n.add_argument("--top", type=int, default=10)
    a = sub.add_parser("app")
    a.add_argument("log")
    p = sub.add_parser("pg")
    p.add_argument("log")
    args = parser.parse_args()
    {"nginx": nginx_report, "app": app_report, "pg": pg_report}[args.mode](args)


if __name__ == "__main__":
    sys.exit(main())
