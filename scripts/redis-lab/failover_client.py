# 실험 2: Sentinel을 아는 쓰기 클라이언트. 사용: failover_client.py 센티널IP,센티널IP,... 모드(plain|wait) 쓰기스레드수
# READY를 출력한 뒤 호스트가 마스터를 죽인다. 장애를 겪고 3초 이상 안정적으로 쓰기가 되면 요약을 JSON으로 출력한다
# 쓰기마다 고유 키(w:스레드:번호)를 쓰고, 끝에 최종 마스터에 확인받은 키가 남아 있는지 MGET으로 검증한다(partition_client.py와 같은 방식)
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
acked_ids = [set() for _ in range(WRITERS)]     # 성공 응답을 받은 키 번호(스레드별)
unacked_ids = [set() for _ in range(WRITERS)]   # WAIT이 복제를 확인하지 못한 키 번호
ack_times = []                                  # 확인된 쓰기의 시각(장애 전 처리량 계산용)
errors = []                    # (시각, 종류)
successes = []                 # 성공 시각
stop = threading.Event()
state = {"addr": resolve()}


def writer(idx):
    conn, addr, n = None, None, 0
    while not stop.is_set():
        cur = state["addr"]
        n += 1
        try:
            if conn is None or addr != cur:
                if conn:
                    conn.close()
                conn, addr = R(*cur), cur
            conn.cmd("SET", f"w:{idx}:{n}", 1)
            if MODE == "wait" and conn.cmd("WAIT", 1, 100) < 1:
                with lock:
                    unacked_ids[idx].add(n)
            else:
                with lock:
                    acked_ids[idx].add(n)
                    ack_times.append(time.time())
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


threads = [threading.Thread(target=writer, args=(i,)) for i in range(WRITERS)]
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
missing_acked = missing_unacked = 0
try:
    c = R(*new_addr, 5.0)
    new_role = c.cmd("ROLE")[0]
    for idx in range(WRITERS):
        for ids, is_acked in ((acked_ids[idx], True), (unacked_ids[idx], False)):
            ordered = sorted(ids)
            for i in range(0, len(ordered), 500):
                chunk = ordered[i:i + 500]
                gone = sum(1 for v in c.cmd("MGET", *[f"w:{idx}:{k}" for k in chunk]) if v is None)
                if is_acked:
                    missing_acked += gone
                else:
                    missing_unacked += gone
except Exception as e:
    new_role = f"검증 실패: {e}"
    missing_acked = None
n_acked = sum(len(a) for a in acked_ids)
n_unacked = sum(len(u) for u in unacked_ids)
pre = [t for t in ack_times if first_err and t < first_err]
reasons = {}
for _, k in errors:
    reasons[k[:30]] = reasons.get(k[:30], 0) + 1
print(json.dumps({
    "mode": MODE, "writers": WRITERS,
    "first_error_at": first_err, "last_error_at": last_err, "recovered_at": recovered_at,
    "outage_s": round(recovered_at - first_err, 2) if recovered_at and first_err else None,
    "acked_total": n_acked, "unacked_total": n_unacked,
    "writes_per_s_before": round(len(pre) / (first_err - t_ready + 3), 0) if first_err else None,
    "errors_total": len(errors), "error_reasons": reasons,
    "new_master": f"{new_addr[0]}:{new_addr[1]}", "new_role": new_role,
    "lost_acked_writes": missing_acked,
    "unacked_that_exist": (n_unacked - missing_unacked) if missing_acked is not None else None,
}, ensure_ascii=False), flush=True)
