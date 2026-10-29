# 실험 1: Redis 락이 깨지는 상황을 재현한다. 사용: lock_demo.py 호스트 포트
# 환경변수 SEAT_CLAIM_LUA, SEAT_RELEASE_LUA에 앱의 좌석 스크립트 내용을 넘긴다
import json, os, socket, sys, threading, time

HOST, PORT = sys.argv[1], int(sys.argv[2])


class R:
    """의존성 없는 최소 RESP 클라이언트"""

    def __init__(self):
        self.s = socket.create_connection((HOST, PORT))
        self.s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.f = self.s.makefile("rb")

    def cmd(self, *args):
        out = b"*%d\r\n" % len(args)
        for a in args:
            b = str(a).encode()
            out += b"$%d\r\n%s\r\n" % (len(b), b)
        self.s.sendall(out)
        return self._read()

    def _read(self):
        line = self.f.readline().rstrip(b"\r\n")
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


SAFE_UNLOCK = "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end"
EXTEND = "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('pexpire',KEYS[1],ARGV[2]) else return 0 end"
T0 = time.monotonic()


def now():
    return time.monotonic() - T0


class Section:
    """임계구역에 들어가 있던 구간과, 그 안에서 정지해 있던 구간을 기록한다"""

    def __init__(self):
        self.spans = []
        self.lock = threading.Lock()

    def add(self, who, a, b, frozen=None):
        with self.lock:
            self.spans.append((who, a, b, frozen or []))

    @staticmethod
    def _active(a, b, frozen):
        segs, cur = [], a
        for fs, fe in sorted(frozen):
            if fs > cur:
                segs.append((cur, fs))
            cur = max(cur, fe)
        if cur < b:
            segs.append((cur, b))
        return segs

    def overlaps(self):
        out = []
        for i, (w1, a1, b1, f1) in enumerate(self.spans):
            for w2, a2, b2, f2 in self.spans[i + 1:]:
                held = min(b1, b2) - max(a1, a2)
                if held <= 0.01:
                    continue
                work = sum(max(0, min(e1, e2) - max(s1, s2))
                           for s1, e1 in self._active(a1, b1, f1) for s2, e2 in self._active(a2, b2, f2))
                out.append({"pair": f"{w1}/{w2}", "둘다_락을_가졌다고_믿은_ms": round(held * 1000), "실제로_둘다_일한_ms": round(work * 1000)})
        return out

    def spans_ms(self):
        out = {}
        for w, a, b, f in sorted(self.spans, key=lambda x: x[1]):
            out[w] = {"구간": [round(a * 1000), round(b * 1000)]}
            if f:
                out[w]["정지"] = [[round(s * 1000), round(e * 1000)] for s, e in f]
        return out


def acquire(r, key, token, ttl_ms):
    return r.cmd("SET", key, token, "NX", "PX", ttl_ms) == "OK"


def wait_acquire(r, key, token, ttl_ms, timeout=6):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if acquire(r, key, token, ttl_ms):
            return True
        time.sleep(0.05)
    return False


def worker(sec, who, key, ttl_ms, work_s, release, start_delay=0.0, watchdog=False, freeze=None):
    """락을 얻고 work_s 동안 일한 뒤 release('naive' 또는 'safe')로 푼다. freeze=(시작초, 길이초)는 GC 정지 같은 멈춤"""
    time.sleep(start_delay)
    r = R()
    if not wait_acquire(r, key, who, ttl_ms):
        return
    a = now()
    stop = threading.Event()
    frozen = threading.Event()
    if watchdog:
        def wd():
            rr = R()
            while not stop.wait(ttl_ms / 3000):
                if not frozen.is_set():
                    rr.cmd("EVAL", EXTEND, 1, key, who, ttl_ms)
        threading.Thread(target=wd, daemon=True).start()
    done = time.monotonic() + work_s
    frozen_spans = []
    if freeze:
        time.sleep(freeze[0])
        started = now()
        frozen.set()
        time.sleep(freeze[1])
        frozen.clear()
        frozen_spans.append((started, now()))
    time.sleep(max(0, done - time.monotonic()))
    b = now()
    stop.set()
    sec.add(who, a, b, frozen_spans)
    if release == "naive":
        r.cmd("DEL", key)
    else:
        r.cmd("EVAL", SAFE_UNLOCK, 1, key, who)


def run(threads):
    failures = []

    def guarded(fn):
        def inner():
            try:
                fn()
            except Exception as e:  # 작업 스레드의 실패가 join에서 사라지지 않게 모았다가 다시 던진다
                failures.append(e)
        return inner

    ts = [threading.Thread(target=guarded(t)) for t in threads]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    if failures:
        raise failures[0]


def scenario(name, build):
    global T0
    R().cmd("FLUSHALL")
    T0 = time.monotonic()
    sec = Section()
    run(build(sec))
    print(json.dumps({"scenario": name, "spans_ms": sec.spans_ms(), "overlaps": sec.overlaps()}, ensure_ascii=False))


KEY = "lock:demo"

# 1a: TTL(1초)이 작업(2초)보다 짧으면 A가 일하는 중에 B가 락을 얻는다
scenario("1a TTL보다 긴 작업", lambda s: [
    lambda: worker(s, "A", KEY, 1000, 2.0, "safe"),
    lambda: worker(s, "B", KEY, 1000, 0.5, "safe", start_delay=0.1),
])
# 1b: A가 만료된 뒤 DEL로 풀면 B의 락을 지워 C가 B와 겹친다. 소유자 확인(Lua) 해제는 막는다
for mode in ("naive", "safe"):
    scenario(f"1b 해제 방식={mode}", lambda s, mode=mode: [
        lambda: worker(s, "A", KEY, 1000, 1.5, mode),
        lambda: worker(s, "B", KEY, 5000, 2.0, "safe", start_delay=1.05),
        lambda: worker(s, "C", KEY, 5000, 0.5, "safe", start_delay=1.1),
    ])
# 1c: watchdog(연장)이 돌면 TTL이 짧아도 B는 A가 끝나기를 기다린다
scenario("1c watchdog 켬", lambda s: [
    lambda: worker(s, "A", KEY, 1000, 2.5, "safe", watchdog=True),
    lambda: worker(s, "B", KEY, 1000, 0.5, "safe", start_delay=0.1),
])
# 1d: 같은 watchdog도 프로세스가 통째로 멈추면(GC 정지 등) 연장하지 못해 다시 깨진다
scenario("1d watchdog 켬 + 1.5초 정지", lambda s: [
    lambda: worker(s, "A", KEY, 1000, 2.5, "safe", watchdog=True, freeze=(0.3, 1.5)),
    lambda: worker(s, "B", KEY, 1000, 0.5, "safe", start_delay=0.1),
])

# 1e: fencing token. 정지했다 깨어난 A의 낡은 쓰기를 자원 쪽이 토큰으로 거부한다
r = R()
r.cmd("FLUSHALL")
for fencing in (False, True):
    state = {"max_token": 0, "accepted": [], "rejected": []}

    def write(who, token):
        if fencing and token < state["max_token"]:
            state["rejected"].append(who)
            return
        state["max_token"] = max(state["max_token"], token)
        state["accepted"].append(who)

    r.cmd("FLUSHALL")
    tokenA = r.cmd("INCR", "lock:fence")
    acquire(r, KEY, "A", 500)
    time.sleep(0.6)  # A가 정지하는 동안 락이 만료된다
    tokenB = r.cmd("INCR", "lock:fence")
    acquire(r, KEY, "B", 5000)
    write("B", tokenB)
    write("A", tokenA)  # 깨어난 A의 낡은 쓰기
    print(json.dumps({"scenario": f"1e fencing={'켬' if fencing else '끔'}", "tokens": {"A": tokenA, "B": tokenB},
                      "accepted": state["accepted"], "rejected": state["rejected"]}, ensure_ascii=False))

# 1f: 앱의 좌석 스크립트(seat-claim.lua, seat-release.lua)는 소유 토큰으로 안전한 형태다
claim, release = os.environ["SEAT_CLAIM_LUA"], os.environ["SEAT_RELEASE_LUA"]
r.cmd("FLUSHALL")
seat = "seat:hold:1:1"
res = {}
res["A가 선점"] = r.cmd("EVAL", claim, 1, seat, "tokenA", 300)
res["B가 같은 좌석 선점 시도(A 보유 중)"] = r.cmd("EVAL", claim, 1, seat, "tokenB", 5000)
time.sleep(0.4)
res["A의 TTL 만료 뒤 B가 선점"] = r.cmd("EVAL", claim, 1, seat, "tokenB", 5000)
r.cmd("EVAL", release, 1, seat, "tokenA")
res["A가 늦게 해제한 뒤 키의 소유자"] = r.cmd("GET", seat)
r.cmd("EVAL", release, 1, seat, "tokenB")
res["B가 해제한 뒤 키"] = r.cmd("GET", seat)
print(json.dumps({"scenario": "1f 앱의 좌석 스크립트", "result": res}, ensure_ascii=False))


# 1g: 락 안에서 공유 카운터를 읽고, 잠깐 쉬고, 쓰는 일을 100번씩 두 클라이언트가 한다. 락이 깨지면 갱신이 실제로 유실된다
def rmw_worker(who, ttl_ms, times, watchdog, start_delay=0.0):
    time.sleep(start_delay)
    rr = R()
    if not wait_acquire(rr, KEY, who, ttl_ms, timeout=15):
        return
    stop = threading.Event()
    if watchdog:
        def keep():
            w = R()
            while not stop.wait(ttl_ms / 3000):
                w.cmd("EVAL", EXTEND, 1, KEY, who, ttl_ms)
        threading.Thread(target=keep, daemon=True).start()
    for _ in range(times):
        v = int(rr.cmd("GET", "counter") or 0)
        time.sleep(0.005)
        rr.cmd("SET", "counter", v + 1)
    stop.set()
    rr.cmd("EVAL", SAFE_UNLOCK, 1, KEY, who)


TIMES = 100
for label, ttl, wd in (("TTL이 작업보다 충분히 김(5초)", 5000, False), ("TTL이 작업보다 짧음(100ms)", 100, False), ("TTL 100ms + watchdog", 100, True)):
    r.cmd("FLUSHALL")
    r.cmd("SET", "counter", 0)
    run([lambda: rmw_worker("A", ttl, TIMES, wd), lambda: rmw_worker("B", ttl, TIMES, wd, start_delay=0.05)])
    actual = int(r.cmd("GET", "counter"))
    print(json.dumps({"scenario": f"1g 공유 카운터, {label}", "기대값": 2 * TIMES, "실제값": actual, "유실된_갱신": 2 * TIMES - actual}, ensure_ascii=False))


# 1h: 같은 조건(TTL 100ms로 락이 깨짐)에서 자원 쪽이 조건부 쓰기를 하면, 락이 깨져도 갱신이 유실되지 않는다
CAS = "if redis.call('get',KEYS[1])==ARGV[1] then redis.call('set',KEYS[1],ARGV[2]) return 1 else return 0 end"
conflicts = {"n": 0}


def cas_worker(who, ttl_ms, times, start_delay=0.0):
    time.sleep(start_delay)
    rr = R()
    if not wait_acquire(rr, KEY, who, ttl_ms, timeout=15):
        return
    done = 0
    while done < times:
        v = int(rr.cmd("GET", "counter") or 0)
        time.sleep(0.005)
        if rr.cmd("EVAL", CAS, 1, "counter", v, v + 1) == 1:
            done += 1
        else:
            with lock_conflicts:
                conflicts["n"] += 1
    rr.cmd("EVAL", SAFE_UNLOCK, 1, KEY, who)


lock_conflicts = threading.Lock()
r.cmd("FLUSHALL")
r.cmd("SET", "counter", 0)
run([lambda: cas_worker("A", 100, TIMES), lambda: cas_worker("B", 100, TIMES, start_delay=0.05)])
actual = int(r.cmd("GET", "counter"))
print(json.dumps({"scenario": "1h 공유 카운터, TTL 100ms(락이 깨짐) + 조건부 쓰기", "기대값": 2 * TIMES, "실제값": actual,
                  "유실된_갱신": 2 * TIMES - actual, "조건이_어긋나_다시_시도한_횟수": conflicts["n"]}, ensure_ascii=False))
