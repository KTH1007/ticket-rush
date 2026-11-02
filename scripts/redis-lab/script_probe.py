# 1ms 간격 PING 지연을 재면서 Lua 스크립트를 EVALSHA로 한 번 실행하고, 스크립트가 도는 동안 다른 연결이 얼마나 멈췄는지 요약한다
# 사용: script_probe.py 호스트 사전초 사후초   (환경 변수 LUA = 스크립트 본문)
import json, os, socket, sys, threading, time

HOST, PRE, POST = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
LUA = os.environ["LUA"]
KEYS = ["queue:1:waiting", "queue:1:seq", "queue:1:lastPromotedSeq", "queue:activeEventIds", "queue:1:cleaned"]
ARGS = [1, 0, 86400]
if os.environ.get("KEYS_JSON"):
    KEYS, ARGS = json.loads(os.environ["KEYS_JSON"]), json.loads(os.environ["ARGS_JSON"])


class R:
    def __init__(self):
        self.s = socket.create_connection((HOST, 6379), timeout=30)
        self.s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.f = self.s.makefile("rb")

    def cmd(self, *args):
        out = b"*%d\r\n" % len(args)
        for a in args:
            b = a if isinstance(a, bytes) else str(a).encode()
            out += b"$%d\r\n%s\r\n" % (len(b), b)
        self.s.sendall(out)
        return self._read()

    def _read(self):
        line = self.f.readline()
        if not line:
            raise ConnectionError("연결이 닫힘")
        line = line.rstrip(b"\r\n")
        t, rest = line[:1], line[1:]
        if t == b"+":
            return rest.decode()
        if t == b"-":
            raise RuntimeError(rest.decode())
        if t == b":":
            return int(rest)
        if t == b"$":
            n = int(rest)
            return None if n < 0 else self.f.read(n + 2)[:-2].decode()
        if t == b"*":
            n = int(rest)
            return None if n < 0 else [self._read() for _ in range(n)]
        raise RuntimeError(f"알 수 없는 응답: {line!r}")


ctl = R()
sha = ctl.cmd("SCRIPT", "LOAD", LUA)
samples = []
stop = threading.Event()
t_start = time.perf_counter()


def prober():
    c = R()
    while not stop.is_set():
        t0 = time.perf_counter()
        c.cmd("PING")
        samples.append((t0 - t_start, (time.perf_counter() - t0) * 1000))
        time.sleep(0.001)


th = threading.Thread(target=prober, daemon=True)
th.start()
time.sleep(PRE)
t0 = time.perf_counter()
removed = ctl.cmd("EVALSHA", sha, len(KEYS), *KEYS, *ARGS)
cmd_ms = (time.perf_counter() - t0) * 1000
cmd_at = t0 - t_start
time.sleep(POST)
stop.set()
th.join()

lat = sorted(x[1] for x in samples)
worst = max(samples, key=lambda x: x[1])
print(json.dumps({
    "result_len_or_count": (len(removed) if isinstance(removed, list) else removed), "script_ms": round(cmd_ms, 1),
    "ping_max_ms": round(worst[1], 1), "ping_max_after_cmd_s": round(worst[0] - cmd_at, 2),
    "ping_over10ms": sum(1 for x in samples if x[1] > 10), "ping_p99_ms": round(lat[int(len(lat) * 0.99)], 2),
    "samples": len(samples),
}))
