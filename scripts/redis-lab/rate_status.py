# 순번 조회(queue-status.lua)를 정해진 초당 속도로 보낸다. 10개씩 묶어 보내며 무작위 토큰 번호를 쓴다
# 사용: rate_status.py 호스트 토큰수 초당요청 초 스크립트해시
import random, socket, sys, time

HOST, NTOK, RATE, SECONDS, SHA = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
s = socket.create_connection((HOST, 6379), timeout=30)
s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
f = s.makefile("rb")


def enc(*args):
    out = b"*%d\r\n" % len(args)
    for a in args:
        b = str(a).encode()
        out += b"$%d\r\n%s\r\n" % (len(b), b)
    return out


def read_reply():
    line = f.readline().rstrip(b"\r\n")
    t = line[:1]
    if t == b"*":
        for _ in range(int(line[1:])):
            read_reply()
    elif t == b"$":
        n = int(line[1:])
        if n >= 0:
            f.read(n + 2)


rnd = random.Random(7)
batch = 10
interval = batch / RATE
nxt = time.perf_counter()
end = nxt + SECONDS
sent = 0
while time.perf_counter() < end:
    out = b""
    for _ in range(batch):
        i = rnd.randrange(NTOK)
        out += enc("EVALSHA", SHA, 4, "queue:1:token:%012d" % i, "queue:1:active:%012d" % i, "queue:1:lastPromotedSeq", "queue:1:cleaned", 7200)
    s.sendall(out)
    for _ in range(batch):
        read_reply()
    sent += batch
    nxt += interval
    d = nxt - time.perf_counter()
    if d > 0:
        time.sleep(d)
    else:
        nxt = time.perf_counter()
print(f"보낸 요청 {sent}개, 실제 속도 {sent / SECONDS:.0f}/s")
