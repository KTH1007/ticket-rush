#!/usr/bin/env python3
"""대기열 부하 측정 결과(k6 요약, docker stats 표본, 전후 스냅샷, k6 CSV)를 마크다운 표로 바꾼다. 표준 라이브러리만 쓴다.

  steps    <summary.json> <접두사> <도착률,...> <단계초> <간격초> <stats.tsv> <시작epoch> [app.tsv]
  limit    <summary.json> <접두사> <도착률,...> <단계초> <간격초> <stats.tsv> <시작epoch> [app.tsv]   (nginx 상한 검증용, 503을 따로 센다)
  compare  <결과폴더> <이름 템플릿({limit},{rep})> <접두사> <도착률> <상한,...> <반복수> <단계초>   (상한 후보를 반복 측정한 결과를 한 표로)
  intervals <응답.jsonl> <대기자 수> <요청 수를 적을 파일>   (서버가 알려준 조회 간격으로 초당 순번 조회 요청 수 계산)
  totals   <summary.json>   (한 번의 실행 전체: 보낸 요청, 받아줌, 거절, 실패, 못 보낸 반복)
  seconds  <csv> <시작초> <끝초> [기준epoch]
  snapdiff <before.json> <after.json>
"""
import csv
import json
import os
import sys


def metric(summary, name, key, default=0.0):
    entry = summary.get("metrics", {}).get(name)
    if entry is None:
        return default
    for k in (key, "value", "values"):
        if k in entry and not isinstance(entry[k], dict):
            return entry[k]
    return default


def read_stats(path):
    rows = []
    try:
        with open(path) as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) == 4:
                    rows.append((int(parts[0]), parts[1], float(parts[2]), float(parts[3])))
    except FileNotFoundError:
        pass
    return rows


def window_max(rows, role, lo, hi, column):
    values = [r[column] for r in rows if r[1] == role and lo <= r[0] <= hi]
    return max(values) if values else float("nan")


def read_app_stats(path):
    rows = []
    try:
        with open(path) as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) == 3:
                    rows.append((int(parts[0]), float(parts[1]), float(parts[2])))
    except (FileNotFoundError, TypeError):
        pass
    return rows


def app_max(rows, lo, hi, column):
    values = [r[column] for r in rows if lo <= r[0] <= hi]
    return max(values) if values else float("nan")


def steps(summary_path, prefix, rates, step, gap, stats_path, t0, app_stats_path=None):
    summary = json.load(open(summary_path))
    stats = read_stats(stats_path)
    app_stats = read_app_stats(app_stats_path)
    print("| 목표 도착률 | 실제 처리량 | 실패율 | 못 보낸 요청 | 지연 평균 | 중앙값 | p95 | p99 | 최대 | 앱 CPU 최대 | Redis CPU 최대 | 컨테이너 메모리 최대 | 힙 사용 최대 | 처리 중 요청 최대 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for i, rate in enumerate(rates):
        name = f"{prefix}_{rate}"
        count = metric(summary, f"http_reqs{{scenario:{name}}}", "count")
        failed = metric(summary, f"http_req_failed{{scenario:{name}}}", "rate")
        dropped = metric(summary, f"dropped_iterations{{scenario:{name}}}", "count")
        d = lambda key: metric(summary, f"http_req_duration{{scenario:{name}}}", key)
        lo = t0 + i * (step + gap) + 3
        hi = lo + step
        print(
            f"| {rate:,}/s | {count / step:,.0f}/s | {failed * 100:.2f}% | {int(dropped):,} | "
            f"{d('avg'):.1f}ms | {d('med'):.1f}ms | {d('p(95)'):.1f}ms | {d('p(99)'):.1f}ms | {d('max'):.0f}ms | "
            f"{window_max(stats, 'app', lo, hi, 2):.0f}% | {window_max(stats, 'redis', lo, hi, 2):.0f}% | "
            f"{window_max(stats, 'app', lo, hi, 3):.0f}MiB | {app_max(app_stats, lo, hi, 1):.0f}MiB | {app_max(app_stats, lo, hi, 2):,.0f} |"
        )


def limit_steps(summary_path, prefix, rates, step, gap, stats_path, t0, app_stats_path=None):
    summary = json.load(open(summary_path))
    stats = read_stats(stats_path)
    app_stats = read_app_stats(app_stats_path)
    print("| 목표 도착률 | 받아준 처리량 | 거절(503) 비율 | 그 밖의 실패율 | 받아준 p95 | 받아준 p99 | 받아준 최대 | 거절 응답 p95 | 앱 CPU 최대 | nginx CPU 최대 | 컨테이너 메모리 최대 | 힙 사용 최대 | 처리 중 요청 최대 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for i, rate in enumerate(rates):
        name = f"{prefix}_{rate}"
        count = metric(summary, f"http_reqs{{scenario:{name}}}", "count")
        rejected = metric(summary, f"busy_503{{scenario:{name}}}", "count")
        failed = metric(summary, f"http_req_failed{{scenario:{name}}}", "rate")
        accepted_count = max(count - rejected - failed * count, 0)
        a = lambda key: metric(summary, f"accepted_ms{{scenario:{name}}}", key, float("nan"))
        b = lambda key: metric(summary, f"busy_ms{{scenario:{name}}}", key, float("nan"))
        lo = t0 + i * (step + gap) + 3
        hi = lo + step
        rejected_ratio = rejected / count * 100 if count else 0.0
        print(
            f"| {rate:,}/s | {accepted_count / step:,.0f}/s | {rejected_ratio:.2f}% | {failed * 100:.2f}% | "
            f"{a('p(95)'):.1f}ms | {a('p(99)'):.1f}ms | {a('max'):.0f}ms | {b('p(95)'):.1f}ms | "
            f"{window_max(stats, 'app', lo, hi, 2):.0f}% | {window_max(stats, 'nginx', lo, hi, 2):.0f}% | "
            f"{window_max(stats, 'app', lo, hi, 3):.0f}MiB | {app_max(app_stats, lo, hi, 1):.0f}MiB | {app_max(app_stats, lo, hi, 2):,.0f} |"
        )


def compare(out_dir, template, prefix, rate, limits, reps, step):
    print("| 상한 | 반복 | 받아준 처리량 | 거절(503) | 그 밖의 실패 | 받아준 p95 | 받아준 p99 | 앱 CPU | nginx CPU | 앱 생존 |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    scenario = f"{prefix}_{rate}"
    for limit in limits:
        rows = []
        for rep in range(1, reps + 1):
            name = template.format(limit=limit, rep=rep)
            try:
                summary = json.load(open(f"{out_dir}/{name}.summary.json"))
            except FileNotFoundError:
                print(f"| {limit:,} | {rep} | (결과 없음) | | | | | | | |")
                continue
            count = metric(summary, f"http_reqs{{scenario:{scenario}}}", "count")
            rejected = metric(summary, f"busy_503{{scenario:{scenario}}}", "count")
            failed = metric(summary, f"http_req_failed{{scenario:{scenario}}}", "rate")
            accepted = max(count - rejected - failed * count, 0) / step
            p95 = metric(summary, f"accepted_ms{{scenario:{scenario}}}", "p(95)", float("nan"))
            p99 = metric(summary, f"accepted_ms{{scenario:{scenario}}}", "p(99)", float("nan"))
            stats = read_stats(f"{out_dir}/{name}.stats.tsv")
            try:
                lo = int(open(f"{out_dir}/{name}.t0").read()) + 3
            except FileNotFoundError:
                lo = 0
            hi = lo + step
            app_cpu = window_max(stats, "app", lo, hi, 2)
            nginx_cpu = window_max(stats, "nginx", lo, hi, 2)
            alive = not os.path.exists(f"{out_dir}/{name}.crashed")
            rejected_ratio = rejected / count * 100 if count else 0.0
            rows.append((accepted, rejected_ratio, failed * 100, p95, p99, alive))
            print(
                f"| {limit:,} | {rep} | {accepted:,.0f}/s | {rejected_ratio:.2f}% | {failed * 100:.2f}% | "
                f"{p95:.1f}ms | {p99:.1f}ms | {app_cpu:.0f}% | {nginx_cpu:.0f}% | {'생존' if alive else '죽음'} |"
            )
        if rows:
            survived = sum(1 for r in rows if r[5])
            print(
                f"| **{limit:,} 요약** | {len(rows)}번 | 최악 {min(r[0] for r in rows):,.0f}/s | 최대 {max(r[1] for r in rows):.2f}% | "
                f"최대 {max(r[2] for r in rows):.2f}% | 최악 {max(r[3] for r in rows):.1f}ms | 최악 {max(r[4] for r in rows):.1f}ms | | | {survived}/{len(rows)} 생존 |"
            )


def percentile(sorted_values, q):
    if not sorted_values:
        return float("nan")
    return sorted_values[min(len(sorted_values) - 1, int(len(sorted_values) * q))]


def seconds(csv_path, lo, hi, base_epoch):
    per_second = {}
    first = None
    with open(csv_path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row["metric_name"] != "http_req_duration":
                continue
            ts = int(float(row["timestamp"]))
            first = ts if first is None else min(first, ts)
            per_second.setdefault(ts, []).append((float(row["metric_value"]), row.get("status", "")))
    base = base_epoch if base_epoch is not None else first
    print("| 기준 대비 초 | 요청 | 성공(2xx) | 409 | 503 | 실패 등 | p50 | p99 | 최대 |")
    print("|---|---|---|---|---|---|---|---|---|")
    for sec in range(lo, hi + 1):
        items = per_second.get(base + sec, [])
        durations = sorted(v for v, _ in items)
        status = [s for _, s in items]
        ok = sum(1 for s in status if s.startswith("2"))
        other = len(status) - ok - status.count("409") - status.count("503")
        print(
            f"| {sec:+d} | {len(items):,} | {ok:,} | {status.count('409'):,} | {status.count('503'):,} | {other:,} | "
            f"{percentile(durations, 0.5):.1f}ms | {percentile(durations, 0.99):.1f}ms | {(durations[-1] if durations else float('nan')):.0f}ms |"
        )


def snapdiff(before_path, after_path):
    a = json.load(open(before_path))
    b = json.load(open(after_path))
    calls = b["evalsha_calls"] - a["evalsha_calls"]
    usec = b["evalsha_usec"] - a["evalsha_usec"]
    per_call = usec / calls if calls else float("nan")
    print(
        f"- Redis 메모리 {a['redis_used_memory'] / 1048576:.0f}MiB -> {b['redis_used_memory'] / 1048576:.0f}MiB, "
        f"키 {a['redis_keys']:,} -> {b['redis_keys']:,}, 스크립트 {calls:,}회 호출당 평균 {per_call:.1f}us, "
        f"Postgres 커밋 +{b['pg_xact'] - a['pg_xact']:,}, Hikari 타임아웃 +{int(b['hikari_timeout'] - a['hikari_timeout'])}, "
        f"GC 정지 +{b['gc_sum'] - a['gc_sum']:.2f}초({int(b['gc_count'] - a['gc_count'])}회), 걸린 시간 {b['t'] - a['t']}초"
        + (f", DB 연결 획득 +{int(b['hikari_acquire'] - a['hikari_acquire']):,}회" if 'hikari_acquire' in a and 'hikari_acquire' in b else "")
    )


def totals(summary_path):
    """한 번의 실행 전체를 한 줄로: 보낸 요청, 받아준 수, 거절(503), 그 밖의 실패, 못 보낸 반복, 받아준 응답 시간."""
    s = json.load(open(summary_path))
    reqs = int(metric(s, "http_reqs", "count"))
    accepted = int(metric(s, "accepted_count", "count"))
    busy = int(metric(s, "busy_503", "count"))
    failed = int(round(metric(s, "http_req_failed", "value") * reqs))
    dropped = int(metric(s, "dropped_iterations", "count"))
    acc = lambda key: metric(s, "accepted_ms", key, float("nan"))
    bus = lambda key: metric(s, "busy_ms", key, float("nan"))
    print("| 보낸 요청 | 받아줌 | 거절(503) | 그 밖의 실패 | 못 보낸 반복(dropped) | 받아준 p95 / p99 | 거절 p95 |")
    print("|---|---|---|---|---|---|---|")
    print(f"| {reqs:,} | {accepted:,} | {busy:,} | {failed:,} | {dropped:,} | {acc('p(95)'):.1f} / {acc('p(99)'):.1f}ms | {bus('p(95)'):.1f}ms |")


def intervals(responses_path, users, rate_path):
    """순번을 고르게 뽑아 받은 응답(rank, nextPollIntervalMs)으로 전체 대기자의 초당 순번 조회 요청 수를 계산한다."""
    rows = []
    with open(responses_path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
                rows.append((int(r["rank"]), int(r["nextPollIntervalMs"])))
            except (ValueError, KeyError):
                continue
    rows = [r for r in rows if r[1] > 0]
    if not rows:
        raise SystemExit("유효한 순번 조회 응답이 없음")
    weight = users / len(rows)
    buckets = [(0, 100, "100위 이내"), (101, 6000, "101 ~ 6,000위"), (6001, 20000, "6,001 ~ 20,000위"),
               (20001, 100000, "20,001 ~ 100,000위"), (100001, 200000, "100,001 ~ 200,000위"), (200001, 10**12, "200,001위 이후")]
    print("| 순번 구간 | 사용자 수(추정) | 다음 조회까지 | 초당 요청 |")
    print("|---|---|---|---|")
    total = 0.0
    for lo, hi, label in buckets:
        sel = [i for r, i in rows if lo <= r <= hi]
        if not sel:
            continue
        rate = sum(weight / (i / 1000) for i in sel)
        total += rate
        print(f"| {label} | {len(sel) * weight:,.0f} | {min(sel) / 1000:.0f} ~ {max(sel) / 1000:.0f}초 | {rate:,.0f} |")
    print(f"| **합계** | {users:,} | | **{total:,.0f}** |")
    with open(rate_path, "w") as f:
        f.write(str(int(total)))


def main(argv):
    command = argv[1]
    if command == "steps":
        steps(argv[2], argv[3], [int(x) for x in argv[4].split(",")], int(argv[5]), int(argv[6]), argv[7], int(argv[8]), argv[9] if len(argv) > 9 else None)
    elif command == "limit":
        limit_steps(argv[2], argv[3], [int(x) for x in argv[4].split(",")], int(argv[5]), int(argv[6]), argv[7], int(argv[8]), argv[9] if len(argv) > 9 else None)
    elif command == "compare":
        compare(argv[2], argv[3], argv[4], int(argv[5]), [int(x) for x in argv[6].split(",")], int(argv[7]), int(argv[8]))
    elif command == "seconds":
        seconds(argv[2], int(argv[3]), int(argv[4]), int(argv[5]) if len(argv) > 5 else None)
    elif command == "intervals":
        intervals(argv[2], int(argv[3]), argv[4])
    elif command == "totals":
        totals(argv[2])
    elif command == "snapdiff":
        snapdiff(argv[2], argv[3])
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
