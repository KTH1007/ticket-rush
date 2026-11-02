# 부하 중 Redis 서버 안쪽을 1초 간격으로 읽는다. 서버의 CPU 할당을 쓰지 않도록 다른 컨테이너에서 네트워크로 읽는다
#  - INFO cpu: 메인 스레드 사용률 = (user+sys 증가분) / 경과 시간
#  - INFO commandstats: EVALSHA 호출 수와 서버가 실행에 쓴 시간(호출당 마이크로초)
#  - INFO stats: 서버가 본 초당 명령 수, 만료된 키 수
#  - PING 왕복: 5ms 간격, 구간별 p50/p99/max (--latency-history와 같은 관점)
# 사용: sampler.py 호스트 [초당샘플수=1]. 1초마다 JSON 한 줄을 출력한다. 멈추려면 SIGTERM
import json, signal, socket, sys, threading, time

HOST = sys.argv[1]
stop = threading.Event()
signal.signal(signal.SIGTERM, lambda *a: stop.set())


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
        if t in (b"+", b"-"):
            return rest.decode()
        if t == b":":
            return int(rest)
        if t == b"$":
            n = int(rest)
            return None if n < 0 else self.f.read(n + 2)[:-2].decode()
        if t == b"*":
            n = int(rest)
            return None if n < 0 else [self._read() for _ in range(n)]
        raise RuntimeError(f"알 수 없는 응답: {line!r}")


def info(c, section):
    d = {}
    for line in c.cmd("INFO", section).split("\r\n"):
        if ":" in line and not line.startswith("#"):
            k, v = line.split(":", 1)
            d[k] = v
    return d


pings, lock = [], threading.Lock()


def pinger():
    c = R()
    while not stop.is_set():
        t0 = time.perf_counter()
        c.cmd("PING")
        with lock:
            pings.append((time.perf_counter() - t0) * 1000)
        time.sleep(0.005)


threading.Thread(target=pinger, daemon=True).start()
c = R()


def snap():
    cpu, st, cs = info(c, "cpu"), info(c, "stats"), info(c, "commandstats")
    ev = cs.get("cmdstat_evalsha", "calls=0,usec=0").split(",")
    evd = dict(x.split("=") for x in ev)
    return {
        "t": time.perf_counter(),
        "main": float(cpu["used_cpu_user_main_thread"]) + float(cpu["used_cpu_sys_main_thread"]),
        "total": float(cpu["used_cpu_user"]) + float(cpu["used_cpu_sys"]),
        "calls": int(evd["calls"]), "usec": int(evd["usec"]),
        "expired": int(st["expired_keys"]), "ops": int(st["instantaneous_ops_per_sec"]),
        "mem": int(info(c, "memory")["used_memory"]),
    }


prev = snap()
while not stop.is_set():
    time.sleep(1.0)
    cur = snap()
    dt = cur["t"] - prev["t"]
    with lock:
        w, pings[:] = sorted(pings), []
    dcalls = cur["calls"] - prev["calls"]
    print(json.dumps({
        "ts": round(time.time(), 1),
        "main_cpu": round((cur["main"] - prev["main"]) / dt, 3),
        "total_cpu": round((cur["total"] - prev["total"]) / dt, 3),
        "evalsha_per_s": round(dcalls / dt),
        "evalsha_us_per_call": round((cur["usec"] - prev["usec"]) / dcalls, 2) if dcalls else None,
        "cmd_cpu_share": round((cur["usec"] - prev["usec"]) / 1e6 / dt, 3),
        "server_ops": cur["ops"], "expired_per_s": round((cur["expired"] - prev["expired"]) / dt),
        "mem_mb": round(cur["mem"] / 1048576, 1),
        "ping_p50": round(w[len(w) // 2], 2) if w else None, "ping_p99": round(w[int(len(w) * 0.99)], 2) if w else None,
        "ping_max": round(w[-1], 1) if w else None,
    }), flush=True)
    prev = cur
