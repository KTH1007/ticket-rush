#!/usr/bin/env python3
"""혼합 부하(k6/mixed.js)의 한 번 실행을 합격선과 대조해 mixed.jsonl에 한 줄로 쌓고, 쌓인 줄을 표로 그린다. 표준 라이브러리만 쓴다.

  row   <결과 폴더> <태그> <라벨> <승격 속도> <사용자 수> <앱 대수> <PG 지연 ms> <장애 모드: yes|no> <앱 생존: yes|no> [순번 조회 초당]
  table <결과 폴더> [태그 접두사]        합격선 표
  windows <결과 폴더> <태그>             구간별 홀드와 결제 응답 시간
  failure <결과 폴더> [태그 접두사]      PG 장애 모드 표

합격선(가정): 홀드 p95 500ms와 p99 1,000ms 이하, 결제 p95는 PG 지연 + 500ms 이하, 409 외 실패 0, Hikari 타임아웃 0과 대기 평균 1 미만,
같은 시간의 순번 조회 p99 100ms 이하, 좌석 상태 p95 100ms 이하, 앱 생존, 앱 CPU 평균(부하 구간) 170% 이하(2코어 한도 200%의 85%)
"""
import json
import math
import os
import sys

HOLD_P95_MS, HOLD_P99_MS = 500, 1000
PAY_MARGIN_MS = 500
STATUS_P95_MS = 100
POLL_P99_MS = 100
APP_CPU_MEAN = 170
STATUS_PER_USER = 7.5  # 체류 15초 / 조회 간격 2초
DROP_RATIO = 0.01


def metric(s, name, key, default=float("nan")):
    entry = s.get("metrics", {}).get(name)
    if entry is None:
        return default
    for source in (entry, entry.get("values", {})):
        if isinstance(source, dict) and key in source and not isinstance(source[key], dict):
            return source[key]
    return default


def count(s, name):
    v = metric(s, name, "count", 0)
    return 0 if isinstance(v, float) and math.isnan(v) else int(v)


def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


def rows(path, width):
    out = []
    try:
        with open(path) as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) == width:
                    out.append(parts)
    except FileNotFoundError:
        pass
    return out


def load_window(stats):
    """부하 구간: 앱 CPU가 40%를 넘은 첫 순간 + 10초부터 마지막 순간 - 5초까지."""
    busy = [int(r[0]) for r in stats if r[1] == "app" and float(r[2]) >= 40]
    if not busy:
        return None
    return busy[0] + 10, busy[-1] - 5


def loaded_cpu(stats, role):
    """부하 구간의 CPU 평균과 최대. 구간은 앱 CPU로 정하고 Postgres와 Redis도 같은 구간으로 본다."""
    samples = [(int(r[0]), float(r[2])) for r in stats if r[1] == role]
    window = load_window(stats)
    if window is None:
        return float("nan"), max((c for _, c in samples), default=float("nan"))
    inside = [c for t, c in samples if window[0] <= t <= window[1]]
    if not inside:
        # 부하가 15초보다 짧으면 구간이 비므로 앱이 바빴던 초 전체를 쓴다
        busy = {int(r[0]) for r in stats if r[1] == "app" and float(r[2]) >= 40}
        inside = [c for t, c in samples if t in busy]
    if not inside:
        return float("nan"), max((c for _, c in samples), default=float("nan"))
    return sum(inside) / len(inside), max(inside)


def build(out, tag, label, rate, users, apps, pg_delay, failure, alive, poll_rate):
    s = load(f"{out}/{tag}.summary.json")
    poll = load(f"{out}/{tag}.load.summary.json")
    before, after = load(f"{out}/{tag}.before.json"), load(f"{out}/{tag}.after.json")
    stats = rows(f"{out}/{tag}.stats.tsv", 4)
    db = rows(f"{out}/{tag}.db.tsv", 4)
    extra = load(f"{out}/{tag}.extra.json")
    hikari = int(after.get("hikari_timeout", 0)) - int(before.get("hikari_timeout", 0)) + int(extra.get("hikari_timeout_app2", 0))
    pending = [float(r[2]) for r in db]
    app_mean, app_max = loaded_cpu(stats, "app")
    pg_mean, pg_max = loaded_cpu(stats, "postgres")
    redis_mean, redis_max = loaded_cpu(stats, "redis")
    reqs = count(poll, "http_reqs")
    busy = count(poll, "busy_503")
    r = {
        "tag": tag, "label": label, "rate": rate, "users": users, "apps": apps, "pg_delay_ms": pg_delay,
        "failure_mode": failure == "yes", "alive": alive == "yes", "poll_rate": poll_rate,
        "hold_201": count(s, "hold_201"), "hold_409": count(s, "hold_409"), "hold_other": count(s, "hold_other"),
        "hold_p50": metric(s, "hold_ms", "med"), "hold_p95": metric(s, "hold_ms", "p(95)"),
        "hold_p99": metric(s, "hold_ms", "p(99)"), "hold_max": metric(s, "hold_ms", "max"),
        "pay_201": count(s, "pay_201"), "pay_409": count(s, "pay_409"), "pay_503": count(s, "pay_503"),
        "pay_other": count(s, "pay_other"), "abandoned": count(s, "abandoned"),
        "pay_p50": metric(s, "pay_ms", "med"), "pay_p95": metric(s, "pay_ms", "p(95)"),
        "pay_p99": metric(s, "pay_ms", "p(99)"), "pay_max": metric(s, "pay_ms", "max"),
        "status_p95": metric(s, "status_ms", "p(95)"), "status_p99": metric(s, "status_ms", "p(99)"),
        "layout_p95": metric(s, "layout_ms", "p(95)"),
        "status_other": count(s, "status_other"), "layout_other": count(s, "layout_other"),
        "dropped_hold": count(s, "dropped_iterations{scenario:hold}"),
        "dropped_status": count(s, "dropped_iterations{scenario:status}"),
        "dropped_layout": count(s, "dropped_iterations{scenario:layout}"),
        "hikari_timeouts": hikari, "hikari_pending_mean": sum(pending) / len(pending) if pending else float("nan"),
        "hikari_pending_max": max(pending, default=float("nan")),
        "hikari_active_max": max((float(r_[1]) for r_ in db), default=float("nan")),
        "pg_active_max": max((float(r_[3]) for r_ in db), default=float("nan")),
        "app_cpu_mean": app_mean, "app_cpu_max": app_max, "pg_cpu_mean": pg_mean, "pg_cpu_max": pg_max,
        "redis_cpu_mean": redis_mean, "redis_cpu_max": redis_max,
        "poll_p99": metric(poll, "accepted_ms", "p(99)"), "poll_p95": metric(poll, "accepted_ms", "p(95)"),
        "poll_busy_share": busy / reqs if reqs else float("nan"), "poll_reqs": reqs,
        "poll_failed": int(round(metric(poll, "http_req_failed", "value", 0) * reqs)),
        "windows": {}, "win_s": int(os.environ.get("H_WIN", 30)),
        "status_on": os.environ.get("H_STATUS", "1") != "0", "layout_on": os.environ.get("H_LAYOUT", "1") != "0",
    }
    for w in range(0, 40):
        h, p = metric(s, f"hold_ms{{win:{w}}}", "p(95)"), metric(s, f"pay_ms{{win:{w}}}", "p(95)")
        # 트렌드 서브메트릭에는 건수가 없다. 비어 있는 구간은 최댓값이 0이다.
        # 구간 경계 직전에 시작한 홀드의 결제가 다음 구간에 끝날 수 있어 결제 값만 있는 구간도 남긴다
        hold_max = metric(s, f"hold_ms{{win:{w}}}", "max", 0)
        pay_max = metric(s, f"pay_ms{{win:{w}}}", "max", 0)
        if hold_max > 0 or pay_max > 0:
            nan = float("nan")
            r["windows"][str(w)] = {
                "hold_p95": h if hold_max > 0 else nan, "hold_max": hold_max if hold_max > 0 else nan,
                "hold_avg": metric(s, f"hold_ms{{win:{w}}}", "avg") if hold_max > 0 else nan,
                "pay_p95": p if pay_max > 0 else nan,
            }
    r.update({k: v for k, v in extra.items()})
    r["verdict"] = verdict(r)
    return r


def missing(r):
    """켠 시나리오인데 측정값이 없는 항목. 값이 없으면 NaN 비교가 거짓이라 조용히 통과하므로 따로 걸러낸다."""
    need = [("홀드", r["hold_p95"]), ("홀드", r["hold_p99"])]
    if r["hold_201"] > r["abandoned"]:
        need.append(("결제", r["pay_p95"]))  # 전원 포기 실행은 결제가 없는 게 정상이다
    if r["status_on"]:
        need.append(("좌석 상태", r["status_p95"]))
    if r["layout_on"]:
        need.append(("좌석 배치", r["layout_p95"]))
    if r["poll_rate"] > 0:
        need.append(("순번 조회", r["poll_p99"]))
    return sorted({name for name, value in need if math.isnan(value)})


def verdict(r):
    """통과하지 못한 항목 이름 목록. 장애 모드는 결제 항목을 판정하지 않는다."""
    fails = [f"{name} 측정 없음" for name in missing(r)]
    if r["hold_p95"] > HOLD_P95_MS:
        fails.append("홀드 p95")
    if r["hold_p99"] > HOLD_P99_MS:
        fails.append("홀드 p99")
    if not r["failure_mode"] and r["pay_p95"] > r["pg_delay_ms"] + PAY_MARGIN_MS:
        fails.append("결제 p95")
    other = r["hold_other"] + r["pay_other"] + r["status_other"] + r["layout_other"]
    if not r["failure_mode"]:
        other += r["pay_503"]
    if other:
        fails.append(f"409 외 실패 {other}")
    # 부하기가 정한 속도를 못 맞춘 반복. 1%를 넘으면 이 실행의 속도를 이름 그대로 믿을 수 없다
    dropped = r["dropped_hold"] + r["dropped_status"] + r["dropped_layout"]
    if (r["dropped_hold"] > DROP_RATIO * r["users"]
            or r["dropped_status"] > DROP_RATIO * r["users"] * STATUS_PER_USER
            or r["dropped_layout"] > DROP_RATIO * r["users"]):
        fails.append(f"부하기 드롭 {dropped}")
    if r["hikari_timeouts"] or (r["hikari_pending_mean"] >= 1):
        fails.append("Hikari")
    if r["poll_p99"] > POLL_P99_MS:
        fails.append("순번 조회 p99")
    if r["status_p95"] > STATUS_P95_MS:
        fails.append("좌석 상태 p95")
    if not r["alive"]:
        fails.append("앱 죽음")
    if r["app_cpu_mean"] > APP_CPU_MEAN:
        fails.append("앱 CPU")
    return fails


def f(v, digits=0):
    return "-" if isinstance(v, float) and math.isnan(v) else f"{v:,.{digits}f}"


def read_records(out, prefix=""):
    path = f"{out}/mixed.jsonl"
    if not os.path.exists(path):
        return []
    with open(path) as fh:
        return [r for r in (json.loads(line) for line in fh if line.strip()) if r["tag"].startswith(prefix)]


def table(out, prefix):
    print("| 케이스 | 홀드 201 / 409 | 홀드 p95 / p99 | 결제 p95 | 좌석 상태 p95 | 순번 조회 p99 (503 비율) | 409 외 실패 | 드롭(홀드 / 상태) | Hikari 타임아웃 / 대기 평균 | 앱 CPU 평균 / 최대 | Postgres CPU 평균 / 최대 | 판정 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in read_records(out, prefix):
        other = r["hold_other"] + r["pay_other"] + r["status_other"] + r["layout_other"] + (0 if r["failure_mode"] else r["pay_503"])
        ok = "통과" if not r["verdict"] else "탈락: " + ", ".join(r["verdict"])
        print(
            f"| {r['label']} | {r['hold_201']:,} / {r['hold_409']:,} | {f(r['hold_p95'], 1)} / {f(r['hold_p99'], 1)}ms | {f(r['pay_p95'], 1)}ms | "
            f"{f(r['status_p95'], 1)}ms | {f(r['poll_p99'], 1)}ms ({r['poll_busy_share'] * 100:.0f}%) | {other:,} | {r['dropped_hold']:,} / {r['dropped_status']:,} | "
            f"{r['hikari_timeouts']} / {f(r['hikari_pending_mean'], 2)} | {f(r['app_cpu_mean'])}% / {f(r['app_cpu_max'])}% | "
            f"{f(r['pg_cpu_mean'])}% / {f(r['pg_cpu_max'])}% | {ok} |"
        )


def windows(out, tag):
    for r in read_records(out, tag):
        if r["tag"] != tag:
            continue
        step = r.get("win_s", 30)
        print("| 구간 | 홀드 평균 | 홀드 p95 | 홀드 최대 | 결제 p95 |")
        print("|---|---|---|---|---|")
        for w, v in sorted(r["windows"].items(), key=lambda kv: int(kv[0])):
            print(f"| {int(w) * step}~{(int(w) + 1) * step}초 | {f(v['hold_avg'], 1)}ms | {f(v['hold_p95'], 1)}ms | {f(v['hold_max'], 0)}ms | {f(v['pay_p95'], 1)}ms |")


def failure(out, prefix):
    print("| 케이스 | 결제 201 / 503 / 409 / 그 밖 | 결제 p50 / p95 / 최대 | 홀드 p95 | Hikari 타임아웃 | 서킷 차단 호출 | PG 승인 호출 합계 / 초당 최대 | 회복 | 마지막 DB 상태 |")
    print("|---|---|---|---|---|---|---|---|---|")
    for r in read_records(out, prefix):
        print(
            f"| {r['label']} | {r['pay_201']:,} / {r['pay_503']:,} / {r['pay_409']:,} / {r['pay_other']:,} | "
            f"{f(r['pay_p50'], 0)} / {f(r['pay_p95'], 0)} / {f(r['pay_max'], 0)}ms | {f(r['hold_p95'], 1)}ms | {r['hikari_timeouts']} | "
            f"{r.get('cb_not_permitted', '-')} | {r.get('stub_confirm_total', '-')} / {r.get('stub_confirm_peak', '-')} | "
            f"{r.get('recovery', '-')} | {r.get('final_db', '-')} |"
        )


def main(argv):
    cmd = argv[1]
    if cmd == "row":
        out, tag, label, rate, users, apps, pg_delay, failure_mode, alive = argv[2:11]
        poll_rate = int(argv[11]) if len(argv) > 11 else 0
        r = build(out, tag, label, int(rate), int(users), int(apps), int(pg_delay), failure_mode, alive, poll_rate)
        with open(f"{out}/mixed.jsonl", "a") as fh:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"- {label}: " + ("합격선 통과" if not r["verdict"] else "탈락 " + ", ".join(r["verdict"])))
    elif cmd == "table":
        table(argv[2], argv[3] if len(argv) > 3 else "")
    elif cmd == "windows":
        windows(argv[2], argv[3])
    elif cmd == "failure":
        failure(argv[2], argv[3] if len(argv) > 3 else "")
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
