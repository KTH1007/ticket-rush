# 쓰기 부하와 1ms 간격 PING 지연 측정을 걸어 둔 채, 중간에 BGREWRITEAOF 또는 BGSAVE를 실행하고 구간별 지연을 요약한다
# 사용: rewrite_probe.py 호스트 트리거명령 사전초 사후초 쓰기스레드수 스레드당초당쓰기 키모드(token|big) 키개수
#   token: queue:1:token:<토큰>을 SET ... EX 900으로 덮어씀(순번 조회 때 토큰 TTL이 갱신되는 것과 비슷)
#   big:   key:<번호>를 100바이트 값으로 덮어씀(DEBUG POPULATE로 채운 한도 근처 데이터용)
import json, random, socket, sys, threading, time

HOST, TRIGGER = sys.argv[1], sys.argv[2]
PRE, POST = float(sys.argv[3]), float(sys.argv[4])
WRITERS, RATE, KEYMODE, NKEYS = int(sys.argv[5]), int(sys.argv[6]), sys.argv[7], int(sys.argv[8])
PORT = 6379


class R:
    def __init__(self, timeout=5.0):
        self.s = socket.create_connection((HOST, PORT), timeout=timeout)
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


stop = threading.Event()
errors = []
samples = []        # (벽시계 시각, 지연 ms)
writes = [0]
VALUE = b"x" * 100


def writer(seed):
    rnd = random.Random(seed)
    try:
        c = R()
        batch = 10
        interval = batch / RATE
        nxt = time.perf_counter()
        while not stop.is_set():
            out = b""
            for _ in range(batch):
                i = rnd.randint(1, NKEYS)
                if KEYMODE == "token":
                    t = "%08d-1111-4222-8333-%012d" % (i, i)
                    args = (b"SET", ("queue:1:token:" + t).encode(), str(i).encode(), b"EX", b"900")
                else:
                    args = (b"SET", b"key:%d" % (i - 1), VALUE)
                out += b"*%d\r\n" % len(args) + b"".join(b"$%d\r\n%s\r\n" % (len(a), a) for a in args)
            c.s.sendall(out)
            for _ in range(batch):
                c._read()
            writes[0] += batch
            nxt += interval
            d = nxt - time.perf_counter()
            if d > 0:
                time.sleep(d)
            else:
                nxt = time.perf_counter()
    except Exception as e:
        errors.append(("writer", type(e).__name__, str(e)[:60]))


def prober():
    try:
        c = R()
        while not stop.is_set():
            t0 = time.perf_counter()
            c.cmd("PING")
            samples.append((time.time(), (time.perf_counter() - t0) * 1000))
            time.sleep(0.001)
    except Exception as e:
        errors.append(("prober", type(e).__name__, str(e)[:60]))


def pct(xs, p):
    if not xs:
        return None
    xs = sorted(xs)
    return round(xs[min(len(xs) - 1, int(len(xs) * p))], 2)


def summarize(xs):
    return {"n": len(xs), "p50_ms": pct(xs, 0.5), "p99_ms": pct(xs, 0.99), "max_ms": round(max(xs), 2) if xs else None}


def info(c, section):
    d = {}
    for line in c.cmd("INFO", section).split("\r\n"):
        if ":" in line and not line.startswith("#"):
            k, v = line.split(":", 1)
            d[k] = v
    return d


ctl = R(10.0)
threads = [threading.Thread(target=writer, args=(i,)) for i in range(WRITERS)] + [threading.Thread(target=prober)]
for t in threads:
    t.start()
time.sleep(PRE)
flag = "aof_rewrite_in_progress" if TRIGGER == "BGREWRITEAOF" else "rdb_bgsave_in_progress"
t_trigger = time.time()
died = False
try:
    ctl.cmd(TRIGGER)
    t_done = None
    while time.time() - t_trigger < 180:
        time.sleep(0.02)
        if info(ctl, "persistence").get(flag) == "0":
            t_done = time.time()
            break
    if t_done is None:
        t_done = time.time()
except Exception as e:
    errors.append(("control", type(e).__name__, str(e)[:60]))
    died, t_done = True, time.time()
time.sleep(POST)
stop.set()
for t in threads:
    t.join()

before = [l for t, l in samples if t < t_trigger]
during = [l for t, l in samples if t_trigger <= t <= t_done]
after = [l for t, l in samples if t > t_done]
res = {
    "trigger": TRIGGER, "writes_per_s": int(writes[0] / (PRE + (t_done - t_trigger) + POST)),
    "trigger_to_done_s": round(t_done - t_trigger, 2),
    "ping_before": summarize(before), "ping_during": summarize(during), "ping_after": summarize(after),
    "errors": errors[:3], "died": died or bool(errors),
}
try:
    c2 = R(10.0)
    p, m = info(c2, "persistence"), info(c2, "stats")
    mem = info(c2, "memory")
    res.update({
        "latest_fork_usec": m.get("latest_fork_usec"),
        "cow_bytes": p.get("aof_last_cow_size") if TRIGGER == "BGREWRITEAOF" else p.get("rdb_last_cow_size"),
        "used_memory_mb": round(int(mem["used_memory"]) / 1048576, 1),
        "used_memory_peak_mb": round(int(mem["used_memory_peak"]) / 1048576, 1),
    })
except Exception as e:
    res["info_error"] = str(e)[:60]
print(json.dumps(res, ensure_ascii=False), flush=True)
