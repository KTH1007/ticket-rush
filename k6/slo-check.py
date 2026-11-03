# k6 요약(--summary-export)을 docs/slo.md의 SLO와 비교해 한 표로 낸다
#   slo-check.py flow  요약.json                       slo-flow.js 결과(API별 태그)
#   slo-check.py queue 요약.json API 시나리오이름       queue.js 결과(줄서기 register, 순번 조회 poll)
# 목표는 docs/slo.md의 표와 같은 값이다. 하나라도 어기면 표 끝에 위반을 적고 종료 코드 1을 돌려준다
import json
import sys

# API: (p95 ms, p99 ms, 좋은 응답 비율)
SLO = {
    "register": (200, 500, 0.999),
    "poll": (100, 300, 0.999),
    "events_list": (200, 500, 0.999),
    "events_detail": (200, 500, 0.999),
    "seat_status": (100, 200, 0.999),
    "seat_layout": (150, 300, 0.999),
    "hold": (300, 1000, 0.999),
    "pay": (1000, 3000, 0.995),
    "lookup": (200, 500, 0.999),
    "cancel": (1000, 3000, 0.995),
}
ORDER = list(SLO)
HEADER = "| API | 유효 요청 | 제외(의도된 거절) | 좋은 응답 | 목표 | p95 ms | 목표 | p99 ms | 목표 | 판정 | TTFB p95 ms | 받는 시간 p95 ms |"


def row(api, valid, excluded, good, p95, p99, ttfb=None, recv=None):
    t95, t99, tgood = SLO[api]
    violations = []
    if good < tgood:
        violations.append("가용성")
    if p95 > t95:
        violations.append("p95")
    if p99 > t99:
        violations.append("p99")
    verdict = "충족" if not violations else "위반(" + ", ".join(violations) + ")"
    line = (f"| {api} | {valid:,} | {excluded:,} | {good * 100:.2f}% | {tgood * 100:.1f}% | "
            f"{p95:.0f} | {t95} | {p99:.0f} | {t99} | {verdict} | "
            f"{'-' if ttfb is None else format(ttfb, '.0f')} | {'-' if recv is None else format(recv, '.0f')} |")
    return line, violations


def flow(path):
    metrics = json.load(open(path))["metrics"]
    lines, bad = [], []
    for api in ORDER:
        good_m = metrics.get(f"sli_good{{api:{api}}}")
        dur = metrics.get(f"http_req_duration{{api:{api}}}")
        if not good_m or not dur:
            continue
        valid = good_m["passes"] + good_m["fails"]
        excluded = int(metrics.get(f"sli_excluded{{api:{api}}}", {}).get("count", 0))
        waiting = metrics.get(f"http_req_waiting{{api:{api}}}", {}).get("p(95)")
        receiving = metrics.get(f"http_req_receiving{{api:{api}}}", {}).get("p(95)")
        line, violations = row(api, valid, excluded, good_m["value"], dur["p(95)"], dur["p(99)"], waiting, receiving)
        lines.append(line)
        if violations:
            bad.append(f"{api}: {', '.join(violations)}")
    return lines, bad


def queue(path, api, scenario):
    metrics = json.load(open(path))["metrics"]
    reqs = metrics[f"http_reqs{{scenario:{scenario}}}"]["count"]
    failed = metrics[f"http_req_failed{{scenario:{scenario}}}"]["value"]
    busy = int(metrics.get(f"busy_503{{scenario:{scenario}}}", {}).get("count", 0))
    full = int(metrics.get(f"queue_full_503{{scenario:{scenario}}}", {}).get("count", 0))
    # 대기열이 가득 찬 503(QUEUE_FULL)만 의도된 거절로 빼고, nginx 상한이나 장애로 인한 503은 실패로 센다
    valid = reqs - full
    bad = failed * reqs + (busy - full)
    # 지연은 받아준 응답(201, 200)만 본다. 의도된 거절은 빨리 끝나서 섞이면 지연이 좋아 보인다.
    # 전부 의도된 거절이면(가득 찬 대기열에 계속 줄서기) 받아준 응답이 없으므로 거절 응답의 지연을 같은 기준으로 본다
    if valid > 0:
        dur = metrics.get(f"accepted_ms{{scenario:{scenario}}}") or metrics[f"http_req_duration{{scenario:{scenario}}}"]
        good = 1 - bad / valid
    else:
        dur = metrics.get(f"busy_ms{{scenario:{scenario}}}") or metrics[f"http_req_duration{{scenario:{scenario}}}"]
        good = 1.0
    line, violations = row(api, valid, full, good, dur["p(95)"], dur["p(99)"])
    # 거의 전부 의도된 거절이면 분모(유효 요청)가 작아 비율이 흔들리므로, 전체 요청 대비 의도하지 않은 실패도 같이 낸다
    note = f"- 의도하지 않은 실패 {bad:,.0f}건 / 전체 {reqs:,.0f}건 ({bad / reqs * 100:.2f}%)"
    return [line], ([f"{api}: {', '.join(violations)}"] if violations else []), note


def resources(stats_path, db_path, app_path):
    import statistics
    cpu = {}
    app_total = {}
    for line in open(stats_path):
        cols = line.rstrip("\n").split("\t")
        if len(cols) >= 3:
            try:
                value = float(cols[2].rstrip("%"))
            except ValueError:
                continue
            cpu.setdefault(cols[1], []).append(value)
            if cols[1] in ("app", "app2"):
                app_total[cols[0]] = app_total.get(cols[0], 0) + value
    db = [list(map(float, l.split("\t"))) for l in open(db_path) if l.strip()]
    heap = [float(l.split("\t")[1]) for l in open(app_path) if l.strip()]
    parts = [f"{role} CPU 평균 {statistics.mean(v):.0f}% 최대 {max(v):.0f}%" for role, v in sorted(cpu.items())]
    if "app2" in cpu:
        parts.append(f"앱 두 대 합계 평균 {statistics.mean(app_total.values()):.0f}% 최대 {max(app_total.values()):.0f}%")
    parts.append(f"Hikari 사용 중 최대 {max(r[1] for r in db):.0f}, 연결을 기다린 스레드 최대 {max(r[2] for r in db):.0f}")
    parts.append(f"힙 최대 {max(heap):.0f}MiB")
    print("- 자원: " + ", ".join(parts))


def main():
    mode = sys.argv[1]
    if mode == "resources":
        resources(sys.argv[2], sys.argv[3], sys.argv[4])
        return
    note = None
    if mode == "flow":
        lines, bad = flow(sys.argv[2])
    else:
        lines, bad, note = queue(sys.argv[2], sys.argv[3], sys.argv[4])
    print(HEADER)
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    print("\n".join(lines))
    print(f"- SLO 위반: {'; '.join(bad)}" if bad else "- SLO 위반 없음")
    if note:
        print(note)
    if mode in ("flow", "queue"):
        metrics = json.load(open(sys.argv[2]))["metrics"]
        scenario = "" if mode == "flow" else f"{{scenario:{sys.argv[4]}}}"
        dropped = int(metrics.get(f"dropped_iterations{scenario}", {}).get("count", 0))
        started = int(metrics.get("iterations" if mode == "flow" else f"http_reqs{scenario}", {}).get("count", 0))
        if dropped:
            share = dropped / (started + dropped)
            # 시작 직후 몇 건은 흔하다. 0.1%를 넘으면 목표 속도를 다 채우지 못한 측정이다
            if share >= 0.001:
                print(f"- 주의: k6가 시작하지 못한 반복 {dropped}건({share * 100:.1f}%). 목표 도착률을 다 채우지 못한 측정이라 목표 속도의 결과로 읽으면 안 된다")
            else:
                print(f"- 참고: k6가 시작하지 못한 반복 {dropped}건({share * 100:.3f}%)")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
