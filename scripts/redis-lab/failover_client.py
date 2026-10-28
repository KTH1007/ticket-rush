# 실험 2: Sentinel을 아는 쓰기 클라이언트. 사용: failover_client.py 센티널IP,센티널IP,... 모드(plain|wait) 쓰기스레드수
# READY를 출력한 뒤 호스트가 마스터를 죽인다. 장애를 겪고 3초 이상 안정적으로 쓰기가 되면 요약을 JSON으로 출력한다
import json, socket, sys, threading, time

SENTINELS = [(h, 26379) for h in sys.argv[1].split(",")]
MODE, WRITERS = sys.argv[2], int(sys.argv[3])


class R:
    def __init__(self, host, port, timeout=0.5):
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
acked, unacked = [], []        # (시각, INCR 반환값)
errors = []                    # (시각, 종류)
successes = []                 # 성공 시각
stop = threading.Event()
state = {"addr": resolve()}


def writer():
    conn, addr = None, None
    while not stop.is_set():
        cur = state["addr"]
        try:
            if conn is None or addr != cur:
                if conn:
                    conn.close()
                conn, addr = R(*cur), cur
            v = conn.cmd("INCR", "counter")
            if MODE == "wait":
                if conn.cmd("WAIT", 1, 100) >= 1:
                    with lock:
                        acked.append((time.time(), v))
                else:
                    with lock:
                        unacked.append((time.time(), v))
            else:
                with lock:
                    acked.append((time.time(), v))
            with lock:
                successes.append(time.time())
        except Exception as e:
            with lock:
                errors.append((time.time(), type(e).__name__ + ":" + str(e)[:40]))
            if conn:
                conn.close()
            conn = None
            time.sleep(0.05)
            new = resolve()
            if new:
                state["addr"] = new


threads = [threading.Thread(target=writer) for _ in range(WRITERS)]
for t in threads:
    t.start()
time.sleep(3)
print("READY", flush=True)
t_ready = time.time()
deadline = t_ready + 150
while time.time() < deadline:
    time.sleep(0.5)
    with lock:
        if errors and successes and successes[-1] - errors[-1][0] > 0 and successes[-1] > errors[-1][0] + 3:
            break
stop.set()
for t in threads:
    t.join()

first_err = min(e[0] for e in errors) if errors else None
last_err = max(e[0] for e in errors) if errors else None
after = [s for s in successes if first_err and s > last_err]
recovered_at = min(after) if after else None
new_addr = state["addr"]
try:
    c = R(*new_addr, 1.0)
    final_value = int(c.cmd("GET", "counter"))
    new_role = c.cmd("ROLE")[0]
except Exception as e:
    final_value, new_role = None, str(e)
all_acked = sorted(v for _, v in acked)
lost = [v for v in all_acked if final_value is not None and v > final_value] if first_err else []
pre = [a for a in acked if first_err and a[0] < first_err]
kinds = {}
for _, k in errors:
    kinds[k.split(":")[0]] = kinds.get(k.split(":")[0], 0) + 1
reasons = {}
for _, k in errors:
    reasons[k[:30]] = reasons.get(k[:30], 0) + 1
print(json.dumps({
    "mode": MODE, "writers": WRITERS,
    "first_error_at": first_err, "last_error_at": last_err, "recovered_at": recovered_at,
    "outage_s": round(recovered_at - first_err, 2) if recovered_at and first_err else None,
    "acked_total": len(acked), "unacked_total": len(unacked), "writes_per_s_before": round(len(pre) / (first_err - t_ready + 3), 0) if first_err else None,
    "errors_total": len(errors), "error_reasons": reasons,
    "new_master": f"{new_addr[0]}:{new_addr[1]}", "new_role": new_role,
    "counter_on_new_master": final_value, "max_acked": all_acked[-1] if all_acked else None,
    "lost_acked_writes": len(lost),
}, ensure_ascii=False), flush=True)
