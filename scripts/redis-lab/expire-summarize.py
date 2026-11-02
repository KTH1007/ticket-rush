# expire-storm.sh 결과 요약: 실행마다 한 줄. 인자: 결과 폴더
# 만료 구간 = 첫 만료 예정 시각(base)부터 키 수가 0이 될 때까지. 기준선 = 만료 시작 1초 전까지의 표본
import glob, json, os, sys

d = sys.argv[1]


def kv(path):
    out = {}
    for tok in open(path).read().split():
        k, v = tok.split(":", 1)
        out[k] = float(v)
    return out


print("| 모드 | 회차 | 호스트 유휴 % | 기준선 PING 최대 ms | 만료 중 PING 최대 ms | 만료 중 20ms 넘는 초 | 만료 중 메인 스레드 CPU 최대 / 평균 | 첫 감소 후 0까지 초 | 초당 만료 최대 | cycle CPU ms 증가 | 시간 상한 도달 증가 | AOF 증가 MB | rewrite 횟수 | 메모리 변화 MB |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for f in sorted(glob.glob(f"{d}/*.dbsize")):
    base = f[:-7]
    mode, rep = os.path.basename(base).split("-")
    meta = json.load(open(base + ".meta"))
    base_s = meta["base_ms"] / 1000
    db = [tuple(map(int, l.split())) for l in open(f) if l.strip()]
    first_drop = next((r for r, s in db if s < meta["n"]), None)
    done = next((r for r, s in db if s == 0), None)
    end_s = base_s + (done if done is not None else db[-1][0]) / 1000 + 1
    samples = [json.loads(l) for l in open(base + ".samples") if l.strip()]
    pre = [s for s in samples if s["ts"] < base_s - 1]
    dur = [s for s in samples if base_s - 1 <= s["ts"] <= end_s]
    pm = lambda xs: max((s["ping_max"] for s in xs if s["ping_max"] is not None), default=None)
    cpu = [s["main_cpu"] for s in dur]
    b, a = kv(base + ".stats-before"), kv(base + ".stats-after")
    aof = (int(open(base + ".aof-after").read()) - int(open(base + ".aof-before").read())) / 1048576
    mem = (int(open(base + ".mem-after").read()) - int(open(base + ".mem-before").read())) / 1048576
    rw = (int(open(base + ".rw-after").read()) - int(open(base + ".rw-before").read())) if os.path.exists(base + ".rw-after") else "?"
    drain = round((done - first_drop) / 1000, 1) if (done is not None and first_drop is not None) else "미완료"
    print(f"| {mode} | {rep} | {open(base + '.idle').read().strip()} | {pm(pre)} | {pm(dur)} | "
          f"{sum(1 for s in dur if s['ping_max'] and s['ping_max'] > 20)} | "
          f"{max(cpu):.2f} / {sum(cpu) / len(cpu):.2f} | {drain} | {max(s['expired_per_s'] for s in dur):,} | "
          f"{a.get('expire_cycle_cpu_milliseconds', 0) - b.get('expire_cycle_cpu_milliseconds', 0):.0f} | "
          f"{a.get('expired_time_cap_reached_count', 0) - b.get('expired_time_cap_reached_count', 0):.0f} | {aof:.1f} | {rw} | {mem:.1f} |")
