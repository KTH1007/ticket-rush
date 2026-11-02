# 쓰기마다 고유 키(w:스레드:번호)를 쓰고, 서버가 SIGKILL로 죽었다 돌아오면 확인받은 쓰기가 남아 있는지 MGET으로 검증한다
# 사용: crash_client.py 호스트 쓰기스레드수. READY를 출력한 뒤 호스트가 서버를 죽이고 다시 띄운다
import json, socket, sys, threading, time

HOST, WRITERS, PORT = sys.argv[1], int(sys.argv[2]), 6379


class R:
    def __init__(self, host, port, timeout=2.0):
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


max_acked = [0] * WRITERS   # 스레드마다 번호를 1부터 순서대로 쓰므로 확인받은 쓰기는 1..max_acked
ended_at = [None] * WRITERS
started = time.time()


def writer(idx):
    n = 0
    try:
        c = R(HOST, PORT)
        while True:
            n += 1
            c.cmd("SET", f"w:{idx}:{n}", 1)
            max_acked[idx] = n
    except Exception:
        ended_at[idx] = time.time()


threads = [threading.Thread(target=writer, args=(i,)) for i in range(WRITERS)]
for t in threads:
    t.start()
time.sleep(3)
print("READY", flush=True)
for t in threads:
    t.join()
first_end = min(e for e in ended_at if e)
elapsed = first_end - started

conn = None
deadline = time.time() + 120
while time.time() < deadline:
    try:
        conn = R(HOST, PORT, 10.0)
        if conn.cmd("PING") == "PONG":
            break
    except Exception:
        conn = None
        time.sleep(0.2)
if conn is None:
    print(json.dumps({"error": "서버가 돌아오지 않음"}), flush=True)
    sys.exit(1)

missing_total, first_missing, suffix_only = 0, [], True
for idx in range(WRITERS):
    first, miss = None, 0
    for lo in range(1, max_acked[idx] + 1, 1000):
        hi = min(lo + 1000, max_acked[idx] + 1)
        vals = conn.cmd("MGET", *[f"w:{idx}:{k}" for k in range(lo, hi)])
        for off, v in enumerate(vals):
            if v is None:
                miss += 1
                if first is None:
                    first = lo + off
    missing_total += miss
    first_missing.append(first)
    if first is not None and miss != max_acked[idx] - first + 1:
        suffix_only = False
acked_total = sum(max_acked)
rate = acked_total / elapsed if elapsed > 0 else 0
print(json.dumps({
    "writers": WRITERS, "acked_total": acked_total, "written_for_s": round(elapsed, 1),
    "writes_per_s": int(rate), "missing_acked": missing_total,
    "missing_ms_equiv": round(missing_total / rate * 1000) if rate else None,
    "missing_is_tail_only": suffix_only,
}, ensure_ascii=False), flush=True)
