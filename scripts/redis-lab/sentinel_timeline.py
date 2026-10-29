# 센티널 로그(docker logs -t 출력)에서 페일오버 단계별 첫 시각을 뽑아 kill 시각 기준으로 보여준다
# 사용: sentinel_timeline.py kill시각(RFC3339) 로그파일...
import re, sys
from datetime import datetime

def ts(s):
    s = s.strip().rstrip("Z")
    head, _, frac = s.partition(".")
    return datetime.strptime(head, "%Y-%m-%dT%H:%M:%S").timestamp() + float("0." + (frac or "0"))

kill = ts(sys.argv[1])
EV = ["+sdown master", "+odown master", "+new-epoch", "+try-failover", "+elected-leader", "+selected-slave", "+promoted-slave", "+failover-end", "+switch-master"]
first = {}
for path in sys.argv[2:]:
    for line in open(path, errors="replace"):
        m = re.match(r"(\S+Z)\s+.*?\* ?# ?|(\S+Z)\s+.*", line)
        parts = line.split(None, 1)
        if len(parts) < 2 or not parts[0].endswith("Z"):
            continue
        for e in EV:
            if e in parts[1] and e not in first:
                first[e] = ts(parts[0])
            elif e in parts[1] and ts(parts[0]) < first[e]:
                first[e] = ts(parts[0])
# 실제 시각 순으로 정렬한다. +switch-master는 가장 먼저 본 Sentinel, +failover-end는 레플리카 재설정을 끝낸 리더의 시각이다
prev = kill
for e, t in sorted(first.items(), key=lambda kv: kv[1]):
    print(f"{e}\t{round(t - kill, 2)}\t{round(t - prev, 2)}")
    prev = t
