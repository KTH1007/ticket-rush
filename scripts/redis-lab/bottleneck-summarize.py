# bn1 결과 요약: 실행마다 benchmark의 rps와 지연, 서버 안쪽 지표(정상 상태 구간만), 호스트 유휴율을 한 줄로
import csv, glob, io, json, os, statistics as st, sys

d = sys.argv[1]
rows = []
for f in sorted(glob.glob(f"{d}/*.csv")):
    base = f[:-4]
    name = os.path.basename(base)
    wl, mode, rep = name.split("-")
    raw = open(f).read().strip()
    try:
        rec = next(csv.reader(io.StringIO(raw)))
        rps, p50, p95, p99, mx = float(rec[1]), float(rec[4]), float(rec[5]), float(rec[6]), float(rec[7])
    except Exception:
        print(name, "csv 파싱 실패:", raw[:100]); continue
    samples = [json.loads(l) for l in open(base + ".samples") if l.strip()]
    peak = max((s["evalsha_per_s"] for s in samples), default=0)
    steady = [s for s in samples if s["evalsha_per_s"] >= 0.6 * peak and peak > 0]
    avg = lambda k: round(st.mean(x[k] for x in steady if x[k] is not None), 3) if steady else None
    rows.append({
        "wl": wl, "mode": mode, "rep": rep, "rps": round(rps), "p50": p50, "p95": p95, "p99": p99, "max": mx,
        "srv_per_s": round(st.mean(s["evalsha_per_s"] for s in steady)) if steady else None,
        "us_per_call": avg("evalsha_us_per_call"), "main_cpu": avg("main_cpu"), "cmd_share": avg("cmd_cpu_share"),
        "ping_p99": avg("ping_p99"), "ping_max": max((s["ping_max"] for s in steady if s["ping_max"] is not None), default=None),
        "n_steady": len(steady), "slowlog": open(base + ".slowlen").read().strip(), "idle": open(base + ".idle").read().strip(),
    })
print("| 워크로드 | 설정 | 회차 | benchmark rps | p50 ms | p99 ms | 서버가 본 EVALSHA/s | 호출당 us | 메인 스레드 CPU | 명령 실행이 차지한 CPU | PING p99 ms | PING max ms | SLOWLOG(1ms 초과) | 호스트 유휴 % |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for r in rows:
    print(f"| {r['wl']} | {r['mode']} | {r['rep']} | {r['rps']:,} | {r['p50']} | {r['p99']} | {r['srv_per_s']:,} | {r['us_per_call']} | {r['main_cpu']} | {r['cmd_share']} | {r['ping_p99']} | {r['ping_max']} | {r['slowlog']} | {r['idle']} |")
