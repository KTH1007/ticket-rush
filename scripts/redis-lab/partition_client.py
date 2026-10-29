# 실험 2(분단): 쓰기마다 고유 키(w:스레드:번호)를 쓰고, 끝에서 최종 마스터에 확인받은 키가 남아 있는지 검증한다
# 사용: partition_client.py 센티널IP,... 모드(plain|wait) 스레드수 총시간초 스레드당_초당쓰기
# 클라이언트는 연결된 마스터를 계속 쓰고, 에러가 날 때만 Sentinel에 다시 묻는다(연결 풀을 쓰는 일반 클라이언트의 동작)
import json, socket, sys, threading, time

SENTINELS = [(h, 26379) for h in sys.argv[1].split(",")]
MODE, WRITERS, RUN_S, RATE = sys.argv[2], int(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5])


class R:
    def __init__(self, host, port, timeout=1.0):
        self.s = socket.create_connection((host, port), timeout=timeout)
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

    def close(self):
        try:
            self.s.close()
        except Exception:
            pass


def resolve():
    for host, port in SENTINELS:
        try:
            c = R(host, port, 1.0)
            r = c.cmd("SENTINEL", "get-master-addr-by-name", "mymaster")
            c.close()
            if r:
                return r[0], int(r[1])
        except Exception:
            continue
    return None


lock = threading.Lock()
stop = threading.Event()
acked = [set() for _ in range(WRITERS)]
unacked = [set() for _ in range(WRITERS)]
timeline = {}          # 초 -> [확인됨, 확인안됨(WAIT 0), 에러]
reasons = {}
state = {"addr": resolve(), "switches": []}
T0 = [0.0]


def bump(i, label):
    sec = int(time.time() - T0[0])
    with lock:
        timeline.setdefault(sec, [0, 0, 0])[i] += 1
        if label:
            reasons[label] = reasons.get(label, 0) + 1


def writer(idx):
    conn, addr, n = None, None, 0
    interval = 1.0 / RATE if RATE > 0 else 0
    nxt = time.time()
    while not stop.is_set():
        if interval:
            nxt += interval
            d = nxt - time.time()
            if d > 0:
                time.sleep(d)
            else:
                nxt = time.time()
        n += 1
        cur = state["addr"]
        try:
            if conn is None or addr != cur:
                if conn:
                    conn.close()
                conn, addr = R(*cur), cur
            conn.cmd("SET", f"w:{idx}:{n}", 1)
            if MODE == "wait":
                if conn.cmd("WAIT", 1, 100) >= 1:
                    acked[idx].add(n)
                    bump(0, None)
                else:
                    unacked[idx].add(n)
                    bump(1, None)
            else:
                acked[idx].add(n)
                bump(0, None)
        except Exception as e:
            bump(2, (type(e).__name__ + ":" + str(e))[:34])
            if conn:
                conn.close()
            conn = None
            time.sleep(0.05)
            new = resolve()
            if new and new != state["addr"]:
                state["addr"] = new
                state["switches"].append(round(time.time() - T0[0], 1))


threads = [threading.Thread(target=writer, args=(i,)) for i in range(WRITERS)]
T0[0] = time.time()
for t in threads:
    t.start()
time.sleep(3)
print("READY", flush=True)
time.sleep(RUN_S)
stop.set()
for t in threads:
    t.join()

final = None
for _ in range(20):
    final = resolve()
    if final:
        break
    time.sleep(0.5)
missing_acked = missing_unacked = 0
role = None
try:
    c = R(*final, 5.0)
    role = c.cmd("ROLE")[0]
    for idx in range(WRITERS):
        for group, name in ((acked[idx], "acked"), (unacked[idx], "unacked")):
            ids = sorted(group)
            for i in range(0, len(ids), 500):
                chunk = ids[i:i + 500]
                vals = c.cmd("MGET", *[f"w:{idx}:{n}" for n in chunk])
                miss = sum(1 for v in vals if v is None)
                if name == "acked":
                    missing_acked += miss
                else:
                    missing_unacked += miss
except Exception as e:
    role = f"검증 실패: {e}"
# 초 단위 타임라인을 변화 구간만 압축한다: [초, 확인됨, 확인안됨, 에러]
tl = [[s] + v for s, v in sorted(timeline.items())]
print(json.dumps({
    "mode": MODE, "writers": WRITERS, "rate_per_writer": RATE, "run_s": RUN_S,
    "acked_total": sum(len(a) for a in acked), "unacked_total": sum(len(u) for u in unacked),
    "lost_acked_writes": missing_acked, "unacked_that_exist": sum(len(u) for u in unacked) - missing_unacked,
    "errors_total": sum(v[2] for v in timeline.values()), "error_reasons": reasons,
    "client_switched_at_s": state["switches"], "final_master": f"{final[0]}:{final[1]}" if final else None, "final_role": role,
    "timeline": tl,
}, ensure_ascii=False), flush=True)
