# 다른 연결의 PING 지연을 1ms 간격으로 재면서, 중간에 명령 하나(DEL 또는 UNLINK)를 실행한다
# 사용: probe.py 호스트 포트 명령 키 사전초 사후초
import json, socket, sys, threading, time

host, port, cmd, key = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
pre, post = float(sys.argv[5]), float(sys.argv[6])

def conn():
    s = socket.create_connection((host, port))
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    return s

def call(s, line):
    s.sendall(line.encode() + b"\r\n")
    buf = b""
    while not buf.endswith(b"\r\n"):
        buf += s.recv(256)
    return buf

samples = []
stop = threading.Event()
t_start = time.perf_counter()

def probe():
    s = conn()
    while not stop.is_set():
        t0 = time.perf_counter()
        call(s, "PING")
        t1 = time.perf_counter()
        samples.append((t0 - t_start, (t1 - t0) * 1000))
        time.sleep(0.001)

th = threading.Thread(target=probe)
th.start()
time.sleep(pre)
c = conn()
t0 = time.perf_counter()
reply = call(c, f"{cmd} {key}")
cmd_ms = (time.perf_counter() - t0) * 1000
cmd_at = t0 - t_start
time.sleep(post)
stop.set()
th.join()

lat = sorted(x[1] for x in samples)
worst = max(samples, key=lambda x: x[1])
over10 = [x for x in samples if x[1] > 10]
print(json.dumps({
    "cmd": cmd, "reply": reply.decode().strip(), "cmd_ms": round(cmd_ms, 1), "cmd_at_s": round(cmd_at, 2),
    "samples": len(samples), "p50_ms": round(lat[len(lat) // 2], 2), "p99_ms": round(lat[int(len(lat) * 0.99)], 2),
    "max_ms": round(worst[1], 1), "max_at_s": round(worst[0], 2), "over10ms": len(over10),
}))
